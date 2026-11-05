package komgp.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import komgp.audio.Decoder;
import komgp.audio.Pcm;
import komgp.format.ChartEntry;
import komgp.format.ChartIO;
import komgp.format.ChartPackage;

/**
 * 音频链路探针：从「系统有哪些混音器」一路查到「声卡真的在走帧」。
 *
 * <pre>
 *   java -cp build/dev-classes komgp.dev.AudioProbe
 * </pre>
 */
public final class AudioProbe {

    private AudioProbe() {}

    public static void main(String[] args) throws Exception {
        System.out.println("== Java 音频混音器 ==");
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mi : mixers) {
            Mixer m = AudioSystem.getMixer(mi);
            System.out.printf("  %-40s %s%n", mi.getName(), mi.getDescription());
            DataLine.Info sli = new DataLine.Info(SourceDataLine.class, null);
            try {
                if (m.isLineSupported(sli)) {
                    System.out.println("       playback lines: "
                            + Arrays.toString(m.getSourceLineInfo()).substring(0, 0)
                            + m.getSourceLineInfo().length);
                }
            } catch (Exception ignored) {
                // 有些混音器不实现查询
            }
        }

        AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
        System.out.println("\n== 默认设备是否支持 44.1k/16bit/立体声: "
                + AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, fmt)));

        System.out.println("\n== 打开 SourceDataLine 并写 0.8 秒 440Hz（会出声，音量已调小）==");
        Thread.sleep(1200);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                new DataLine.Info(SourceDataLine.class, fmt));
        line.open(fmt, 1024 * 4);
        line.start();
        System.out.println("  格式=" + line.getFormat() + " 缓冲=" + line.getBufferSize() + " 字节");
        int frames = 44100 * 4 / 5;
        short[] buf = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = i / 44100.0;
            double env = Math.min(1, t * 8) * Math.min(1, (0.8 - t) * 4);
            short v = (short) (Math.sin(2 * Math.PI * 440 * t) * env * 2200);
            buf[i * 2] = v;
            buf[i * 2 + 1] = v;
        }
        byte[] bytes = new byte[buf.length * 2];
        for (int i = 0; i < buf.length; i++) {
            bytes[i * 2] = (byte) buf[i];
            bytes[i * 2 + 1] = (byte) (buf[i] >> 8);
        }
        long t0 = System.nanoTime();
        line.write(bytes, 0, bytes.length);
        line.drain();
        long pos1 = line.getLongFramePosition();
        long t1 = System.nanoTime();
        System.out.printf("  写入完成: 帧位置=%d, 用时 %.0f ms (理论 %.0f ms)%n",
                pos1, (t1 - t0) / 1e6, frames / 44.1);
        line.stop();
        line.close();

        // ---- 解码 + 走我们自己的 AudioEngine
        Path chart = Path.of("charts/01 Neon Pulse.mcz");
        if (!Files.exists(chart)) {
            System.out.println("\n(跳过解码测试：找不到 " + chart + ")");
            return;
        }
        ChartPackage pkg = ChartIO.load(chart, true);
        if (!pkg.ok()) {
            System.out.println("加载失败: " + pkg.error);
            return;
        }
        ChartEntry lc = pkg.charts.get(0);
        Pcm pcm = Decoder.decode(lc.audio, lc.audioName);
        System.out.println("\n== 解码 " + lc.audioName + " ==");
        System.out.println("  " + pcm);
        double rms = pcm.rms();
        System.out.printf("  RMS=%.4f  %s%n", rms, rms > 0.01 ? "（有声音）" : "（疑似静音！）");

        System.out.println("\n== 用 AudioEngine 播 3 秒，看设备时钟 ==");
        komgp.audio.AudioEngine engine = new komgp.audio.AudioEngine();
        engine.setVolume(0.35f);
        engine.loadSong(pcm);
        engine.playFrom(20000, 0);
        long start = System.nanoTime();
        double last = -1;
        for (int i = 0; i < 12; i++) {
            Thread.sleep(250);
            double ms = engine.songMs();
            System.out.printf("  t=%.2fs  歌曲位置=%.1fms  设备=%s  增量=%.1f%n",
                    (System.nanoTime() - start) / 1e9, ms,
                    engine.hasDevice() ? "有" : "无", last < 0 ? 0 : ms - last);
            last = ms;
        }
        engine.stop();
        System.out.println("\n结论: " + (engine.hasDevice()
                ? "走的是真实声卡时钟" : "没有打开声卡（静音时钟）"));
        System.exit(0);
    }
}
