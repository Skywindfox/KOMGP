package komgp.audio;

import java.util.concurrent.locks.LockSupport;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import komgp.util.Log;

/**
 * 打击音效：程序化合成的短音，走独立 SourceDataLine + 混音线程。
 *
 * <p>为什么不直接用 {@link javax.sound.sampled.Clip}：Clip 每次 start 都有几十毫秒的
 * 启动延迟，节奏游戏里会明显感觉到「按下去声音慢半拍」。这里自己混音，
 * 256 帧一块（约 5.8ms），延迟稳定且可控。
 */
public final class Sfx {

    public enum Kind { TAP, MISS, MOVE, OK, COUNT }

    private static final int RATE = 44100;
    private static final int BLOCK = 256;

    private final short[][] bank = new short[Kind.values().length][];
    private final Voice[] voices = new Voice[32];
    private short[] mix = new short[BLOCK];

    private volatile float volume = 0.45f;
    private volatile boolean muted;
    private volatile boolean running;
    private SourceDataLine line;
    private Thread mixer;

    private static final class Voice {
        short[] data;
        int pos;
        float gain;
        boolean active;
    }

    public Sfx() {
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
        bank[Kind.TAP.ordinal()] = tap();
        bank[Kind.MISS.ordinal()] = miss();
        bank[Kind.MOVE.ordinal()] = blip(980, 34, 0.22f);
        bank[Kind.OK.ordinal()] = ok();
        bank[Kind.COUNT.ordinal()] = blip(1320, 70, 0.5f);
    }

    public void setVolume(float v) { volume = Math.max(0, Math.min(1, v)); }

    public void setMuted(boolean m) { muted = m; }

    public void play(Kind k) {
        if (muted || volume <= 0) return;
        short[] data = bank[k.ordinal()];
        if (data == null) return;
        ensureStarted();
        if (line == null) return;
        synchronized (voices) {
            Voice free = null;
            Voice oldest = null;
            for (Voice v : voices) {
                if (!v.active) { free = v; break; }
                if (oldest == null || v.pos > oldest.pos) oldest = v;
            }
            Voice v = free != null ? free : oldest;
            if (v == null) return;
            v.data = data;
            v.pos = 0;
            v.gain = 1f;
            v.active = true;
        }
    }

    public void close() {
        running = false;
        Thread t = mixer;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mixer = null;
        }
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
            line = null;
        }
    }

    private void ensureStarted() {
        if (running) return;
        synchronized (this) {
            if (running) return;
            try {
                AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) {
                    Log.debug("音效不可用（没有输出设备）");
                    return;
                }
                SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
                l.open(fmt, BLOCK * 2 * 4);
                l.start();
                line = l;
                running = true;
                mixer = new Thread(this::loop, "sfx-mixer");
                mixer.setDaemon(true);
                mixer.setPriority(Thread.MAX_PRIORITY);
                mixer.start();
            } catch (Exception e) {
                Log.debug("音效初始化失败: " + e.getMessage());
                line = null;
            }
        }
    }

    private void loop() {
        byte[] bytes = new byte[BLOCK * 2];
        while (running) {
            boolean any = false;
            synchronized (voices) {
                for (Voice v : voices) if (v.active) { any = true; break; }
            }
            if (!any) {
                LockSupport.parkNanos(3_000_000L);
                continue;
            }
            if (mix.length != BLOCK) mix = new short[BLOCK];
            java.util.Arrays.fill(mix, (short) 0);
            synchronized (voices) {
                for (Voice v : voices) {
                    if (!v.active) continue;
                    short[] d = v.data;
                    int i = v.pos;
                    int n = Math.min(BLOCK, d.length - i);
                    float g = v.gain * volume;
                    for (int k = 0; k < n; k++) {
                        int s = mix[k] + (int) (d[i + k] * g);
                        mix[k] = (short) Math.max(-32768, Math.min(32767, s));
                    }
                    v.pos = i + n;
                    if (v.pos >= d.length) {
                        v.active = false;
                        v.data = null;
                    }
                }
            }
            for (int i = 0; i < BLOCK; i++) {
                short s = mix[i];
                bytes[i * 2] = (byte) s;
                bytes[i * 2 + 1] = (byte) (s >> 8);
            }
            SourceDataLine l = line;
            if (l == null) break;
            l.write(bytes, 0, bytes.length);
        }
    }

    // ------------------------------------------------------------------ 合成

    private static short[] alloc(double ms) {
        return new short[(int) Math.max(1, ms * RATE / 1000)];
    }

    /** 打击音：短促的「嗒」，带一点噪声瞬态。 */
    private static short[] tap() {
        short[] d = alloc(55);
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < d.length; i++) {
            double t = i / (double) RATE;
            double env = Math.exp(-t * 70);
            double noise = (rnd.nextDouble() * 2 - 1) * Math.exp(-t * 420) * 0.35;
            double tone = Math.sin(2 * Math.PI * 1650 * t) * 0.55
                    + Math.sin(2 * Math.PI * 3300 * t) * 0.15;
            d[i] = (short) ((tone + noise) * env * 12000);
        }
        return d;
    }

    /** 漏接：低频闷响。 */
    private static short[] miss() {
        short[] d = alloc(200);
        for (int i = 0; i < d.length; i++) {
            double t = i / (double) RATE;
            double env = Math.exp(-t * 16);
            double f = 150 * Math.exp(-t * 6) + 60;
            double v = Math.sin(2 * Math.PI * f * t) * 0.8
                    + Math.sin(2 * Math.PI * f * 2.01 * t) * 0.2;
            d[i] = (short) (v * env * 15000);
        }
        return d;
    }

    private static short[] blip(double freq, double ms, float amp) {
        short[] d = alloc(ms);
        for (int i = 0; i < d.length; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t * 400) * Math.exp(-t * 40);
            d[i] = (short) (Math.sin(2 * Math.PI * freq * t) * env * 9000 * amp * 3);
        }
        return d;
    }

    private static short[] ok() {
        short[] d = alloc(160);
        double[] fs = {880, 1320, 1760};
        int seg = d.length / fs.length;
        for (int s = 0; s < fs.length; s++) {
            for (int i = 0; i < seg; i++) {
                int idx = s * seg + i;
                double t = i / (double) RATE;
                double env = Math.exp(-t * 28);
                d[idx] = (short) (Math.sin(2 * Math.PI * fs[s] * t) * env * 6500);
            }
        }
        return d;
    }
}
