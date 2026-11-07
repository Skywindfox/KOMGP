package komgp.audio;

import java.util.concurrent.locks.LockSupport;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import komgp.util.Log;

/**
 * 歌曲播放 + 主时钟。
 *
 * <p>设计要点（判定精度相关）：
 * <ul>
 *   <li>时钟以「声卡实际播出的帧数」为准，而不是墙钟，这样音符判定与听到的声音对齐；</li>
 *   <li>声卡位置是分块更新的，中间用 nanoTime 线性插值，并夹住最大前推量，避免抖动；</li>
 *   <li>写音频的线程独立于渲染线程，渲染线程只读时钟，绝不阻塞；</li>
 *   <li>没有可用声卡时自动退化为 nanoTime 时钟，游戏照常可玩（仅无声音）。</li>
 * </ul>
 *
 * <p>另外提供不改变时间的「播放速率」：按浮点步长重采样源 PCM，
 * 用于练习时放慢整首曲子（音符时间轴会一起被拉伸，因为时钟是乘过速率的）。
 */
public final class AudioEngine {

    public static final int IDLE = 0;
    public static final int LEAD_IN = 1;
    public static final int PLAYING = 2;
    public static final int PAUSED = 3;
    public static final int ENDED = 4;

    private Pcm song;
    private volatile int state = IDLE;
    private volatile boolean running;
    private Thread feeder;
    private SourceDataLine line;
    private AudioFormat format;

    private volatile float volume = 0.8f;
    private volatile float rate = 1f;

    private double leadInMs;
    /** 从该毫秒处开始播放（编辑器/跳转用）。 */
    private volatile double startOffsetMs;
    private long startNano;
    private volatile double srcPos;          // 已消费的源帧
    private volatile double deviceMs;        // 声卡已播放毫秒（输出流时间）
    /** 曲尾结束时刻；之后时钟按墙钟继续走，保证谱面比音频长时也能正常结算。 */
    private volatile long endedAtNanos;
    private volatile long deviceAtNanos;
    private double pausedSongMs;

    private byte[] writeBuf = new byte[0];

    // ------------------------------------------------------------------ 生命周期

    public boolean hasDevice() { return line != null; }

    public int state() { return state; }

    public Pcm song() { return song; }

    /** 装载新曲目（null 表示无音频，仅用时钟）。 */
    public void loadSong(Pcm pcm) {
        stop();
        this.song = pcm;
        if (pcm != null) {
            Log.debug("歌曲: " + pcm);
        }
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1.5f, v));
    }

    public float volume() { return volume; }

    public void setRate(float r) {
        this.rate = Math.max(0.25f, Math.min(2f, r));
    }

    public float rate() { return rate; }

    /**
     * 从头开始播放。
     *
     * @param leadIn 前置静音时长（毫秒），这段时间里 {@link #songMs()} 从 -leadIn 增长到 0
     */
    public void play(double leadIn) {
        playFrom(0, leadIn);
    }

    /** 从指定歌曲时间开始播放（不足 1ms 的偏差可忽略）。 */
    public void playFrom(double fromMs, double leadIn) {
        stop();
        this.startOffsetMs = Math.max(0, fromMs);
        this.leadInMs = Math.max(0, leadIn);
        this.srcPos = song == null ? 0
                : Math.min(song.frames(), startOffsetMs * song.sampleRate / 1000.0);
        this.deviceMs = 0;
        this.deviceAtNanos = System.nanoTime();
        this.startNano = System.nanoTime();
        if (song != null && !openLine()) {
            // 没有声卡：继续用静音时钟
        }
        state = LEAD_IN;
        running = true;
        feeder = new Thread(this::feederLoop, "audio-feeder");
        feeder.setDaemon(true);
        feeder.setPriority(Thread.MAX_PRIORITY);
        feeder.start();
    }

    public void pause() {
        if (state != PLAYING && state != LEAD_IN) return;
        pausedSongMs = songMs();
        state = PAUSED;
        SourceDataLine l = line;
        if (l != null) l.stop();
    }

    public void resume() {
        if (state != PAUSED) return;
        SourceDataLine l = line;
        if (l != null) {
            l.start();
            deviceAtNanos = System.nanoTime();
        }
        // 把时间基准重新对齐到暂停时刻
        startNano = System.nanoTime() - (long) ((pausedSongMs + leadInMs) * 1e6);
        state = PLAYING;
    }

    public void stop() {
        running = false;
        Thread f = feeder;
        if (f != null) {
            try {
                f.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            feeder = null;
        }
        closeLine();
        state = IDLE;
    }

    public void close() {
        stop();
        song = null;
    }

    // ------------------------------------------------------------------ 时钟

    /** 当前歌曲时间（毫秒）；倒数阶段为负值。 */
    public double songMs() {
        long now = System.nanoTime();
        switch (state) {
            case LEAD_IN: {
                double el = (now - startNano) / 1e6;
                if (line == null) return startOffsetMs + el - leadInMs;
                return Math.min(startOffsetMs + el - leadInMs, startOffsetMs + deviceMs * rate);
            }
            case PLAYING: {
                if (line == null) {
                    // 没有声卡时也用速率推进，练习变速在静音下依然成立
                    return startOffsetMs + ((now - startNano) / 1e6 - leadInMs) * rate;
                }
                double since = (now - deviceAtNanos) / 1e6;
                if (since < 0) since = 0;
                if (since > 30) since = 30; // 写线程卡住时不要无限前推
                return startOffsetMs + (deviceMs + since) * rate;
            }
            case PAUSED:
                return pausedSongMs;
            case ENDED: {
                double dur = song == null ? 0 : song.durationMs();
                long e = endedAtNanos;
                if (e == 0) return dur;
                return dur + (now - e) / 1e6;
            }
            default:
                return 0;
        }
    }

    public double durationMs() {
        return song == null ? 0 : song.durationMs();
    }

    public boolean isPlaying() { return state == PLAYING || state == LEAD_IN; }

    // ------------------------------------------------------------------ 内部

    private boolean openLine() {
        if (line != null) return true;
        try {
            format = new AudioFormat(song.sampleRate, 16, song.channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            AudioFormat probe = new AudioFormat(44100f, 16, 2, true, false);
            if (!AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, probe))
                    && !AudioSystem.isLineSupported(info)) {
                Log.warn("系统没有可用的音频输出设备，使用静音时钟");
                return false;
            }
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
            l.open(format, 1024 * song.channels * 2);
            l.start();
            line = l;
            Log.debug("音频输出: " + format + " buffer=" + l.getBufferSize());
            return true;
        } catch (Exception e) {
            Log.warn("打开音频设备失败，使用静音时钟: " + e.getMessage());
            line = null;
            return false;
        }
    }

    private void closeLine() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Exception ignored) {
                // 关闭异常忽略
            }
        }
    }

    private void feederLoop() {
        if (song == null) {
            // 无音频：只是空转等待 stop()
            while (running) LockSupport.parkNanos(2_000_000L);
            return;
        }
        final int frames = song.sampleRate >= 48000 ? 512 : 1024;
        final int ch = song.channels;
        short[] out = new short[frames * ch];
        int srcFrames = song.frames();
        long lineStartNanos = 0;

        while (running) {
            int st = state;
            if (st == LEAD_IN) {
                long now = System.nanoTime();
                long target = startNano + (long) (leadInMs * 1e6);
                if (now < target) {
                    LockSupport.parkNanos(Math.min(2_000_000L, target - now));
                    continue;
                }
                state = PLAYING;
                lineStartNanos = System.nanoTime();
                continue;
            }
            if (st == PAUSED) {
                LockSupport.parkNanos(3_000_000L);
                continue;
            }
            if (st != PLAYING) break;

            int done = fill(out, frames, srcFrames);
            if (done <= 0) {
                endedAtNanos = System.nanoTime();
                state = ENDED;
                break;
            }
            SourceDataLine l = line;
            if (l != null) {
                int bytes = done * ch * 2;
                if (writeBuf.length < bytes) writeBuf = new byte[bytes * 2];
                toBytes(out, done * ch, writeBuf);
                l.write(writeBuf, 0, bytes);
                deviceMs = l.getLongFramePosition() * 1000.0 / song.sampleRate;
                deviceAtNanos = System.nanoTime();
            } else {
                // 静音模式：按真实时间节流
                if (lineStartNanos == 0) lineStartNanos = System.nanoTime();
                double targetMs = (srcPos / rate) * 1000.0 / song.sampleRate;
                double elapsed = (System.nanoTime() - lineStartNanos) / 1e6;
                long sleep = (long) ((targetMs - elapsed) * 1e6);
                if (sleep > 0) LockSupport.parkNanos(Math.min(sleep, 20_000_000L));
                deviceMs = targetMs;
                deviceAtNanos = System.nanoTime();
            }
        }
        if (running) {
            // 自然结束
            SourceDataLine l = line;
            if (l != null) {
                try {
                    l.drain();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
            endedAtNanos = System.nanoTime();
            state = ENDED;
        }
    }

    /** 把源 PCM 按当前速率/音量填进输出缓冲，返回写入的帧数。 */
    private int fill(short[] out, int frames, int srcFrames) {
        int ch = song.channels;
        double pos = srcPos;
        if (pos >= srcFrames) return 0;
        float g = volume;
        int produced = 0;
        if (Math.abs(rate - 1f) < 1e-4) {
            int start = (int) pos;
            int n = Math.min(frames, srcFrames - start);
            short[] src = song.data;
            if (g == 1f) {
                System.arraycopy(src, start * ch, out, 0, n * ch);
            } else {
                for (int i = 0; i < n * ch; i++) {
                    int v = (int) (src[start * ch + i] * g);
                    out[i] = (short) Math.max(-32768, Math.min(32767, v));
                }
            }
            srcPos = start + n;
            return n;
        }
        short[] src = song.data;
        for (int i = 0; i < frames; i++) {
            int i0 = (int) pos;
            if (i0 >= srcFrames - 1) break;
            double frac = pos - i0;
            for (int c = 0; c < ch; c++) {
                float a = src[i0 * ch + c];
                float b = src[(i0 + 1) * ch + c];
                float v = (float) ((a + (b - a) * frac) * g);
                int iv = (int) v;
                out[i * ch + c] = (short) Math.max(-32768, Math.min(32767, iv));
            }
            pos += rate;
            produced++;
        }
        srcPos = pos;
        return produced;
    }

    private static void toBytes(short[] buf, int count, byte[] b) {
        for (int i = 0; i < count; i++) {
            short s = buf[i];
            b[i * 2] = (byte) s;
            b[i * 2 + 1] = (byte) (s >> 8);
        }
    }
}
