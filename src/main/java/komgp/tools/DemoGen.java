package komgp.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import komgp.format.Json;
import komgp.util.Fonts;
import komgp.util.Log;

/**
 * 演示谱面生成器。
 *
 * <p>游戏本体不含任何素材，这里用代码合成一首 8-bit 风格的曲子、按拍子生成
 * 4K 谱面、画一张曲绘，然后打成 Malody 的 {@code .mc} / {@code .mcz} 包，
 * 用来保证「clone 下来就能玩」以及端到端验证解析链路。
 */
public final class DemoGen {

    private DemoGen() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "charts");
        generateAll(dir);
    }

    // ------------------------------------------------------------------ 歌曲定义

    private record Song(String file, String title, String titleZh, String artist,
                        double bpm, int bars, long seed, String audioExt,
                        int[] chordRoots, float hue) {}

    private static final List<Song> SONGS = List.of(
            new Song("01 Neon Pulse", "Neon Pulse", "霓虹脉冲", "DSH Audio Lab",
                    150, 32, 20240501L, "ogg", new int[]{0, -4, 3, -2}, 0.55f),
            new Song("02 Midnight Drive", "Midnight Drive", "午夜巡游", "DSH Audio Lab",
                    174, 28, 777001L, "mp3", new int[]{-2, -7, 0, 5}, 0.78f),
            new Song("03 Clockwork Garden", "Clockwork Garden", "发条花园", "DSH Audio Lab",
                    128, 24, 31337L, "wav", new int[]{0, 5, 3, -4}, 0.33f)
    );

    public static void generateAll(Path chartsDir) throws IOException {
        Files.createDirectories(chartsDir);
        for (Song s : SONGS) {
            generateCharts(chartsDir, s);
        }
        Log.info("演示谱面已生成到 " + chartsDir.toAbsolutePath());
    }

    private static void generateCharts(Path dir, Song s) throws IOException {
        short[] pcm = synth(s);
        Path tmp = Files.createTempDirectory("malody4k-demo");
        Path wav = tmp.resolve("audio.wav");
        writeWav(wav, pcm, SR, 2);

        Path audio = wav;
        if (!"wav".equals(s.audioExt)) {
            Path converted = tmp.resolve("audio." + s.audioExt);
            if (convert(wav, converted, s.audioExt)) {
                audio = converted;
            } else {
                Log.warn("没有 ffmpeg，退化为 WAV：" + s.file);
            }
        }
        byte[] audioBytes = Files.readAllBytes(audio);
        String audioName = "audio." + extOf(audio);

        BufferedImage cover = drawCover(s);
        BufferedImage bg = drawBackdrop(s, cover);
        byte[] coverPng = png(cover);
        byte[] bgPng = png(bg);

        // 难度：1 首 2 张（同一个 .mcz 里两个 .mc），其余 1 张
        List<String[]> diffs = switch (s.file) {
            case "01 Neon Pulse" -> java.util.List.<String[]>of(
                    new String[]{"Normal", "7", "normal"},
                    new String[]{"Hard", "12", "hard"});
            case "02 Midnight Drive" -> java.util.List.<String[]>of(new String[]{"Insane", "14", "hard"});
            default -> java.util.List.<String[]>of(new String[]{"Easy", "4", "normal"});
        };

        Path mcz = dir.resolve(s.file + ".mcz");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mcz))) {
            for (String[] d : diffs) {
                // 核心：.mc 条目 = 纯 JSON 文本（Malody 当前格式），不是 zip
                byte[] json = Json.write(buildChart(s, d[0], d[1], "hard".equals(d[2]), audioName), true)
                        .getBytes(StandardCharsets.UTF_8);
                put(zos, s.file + " [" + d[0] + "].mc", json);
            }
            put(zos, audioName, audioBytes);
            put(zos, "cover.png", coverPng);
            put(zos, "bg.png", bgPng);
        }
        Log.info("生成 " + mcz.getFileName() + " (" + diffs.size() + " 难度, " + audioName
                + ", " + String.format("%.1f MB", Files.size(mcz) / 1e6) + ")");

        // 再额外导出一份 KOMGP 明文谱面，演示 .kmt 写法
        if (s.file.startsWith("01")) {
            Path textDir = dir.resolve("文本谱示例");
            Files.createDirectories(textDir);
            Files.copy(audio, textDir.resolve(audioName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(textDir.resolve("cover.png"), coverPng);
            Map<String, Object> root = buildChart(s, "Hard", "12", true, audioName);
            @SuppressWarnings("unchecked")
            List<Object> notes = (List<Object>) root.get("note");
            komgp.core.Chart.Builder b = komgp.core.Chart.builder();
            b.timing = komgp.core.Timing.of(new double[]{0}, new double[]{s.bpm}, 4096);
            b.columns = 4;
            b.title = s.title;
            b.titleOrig = s.titleZh;
            b.artist = s.artist;
            b.creator = "AutoDemo";
            b.difficulty = "Hard";
            b.level = "12";
            b.audioEntry = audioName;
            b.coverEntry = "cover.png";
            for (Object o : notes) {
                Map<String, Object> m = Json.asObj(o);
                if (m == null) continue;
                double beat = komgp.format.MalodyLoader.beatOf(m.get("beat"));
                int col = (int) Json.num(m.get("column"), 0);
                double end = komgp.format.MalodyLoader.beatOf(m.get("endbeat"));
                double t = b.timing.msAt(beat);
                boolean hold = !Double.isNaN(end);
                b.add(col, t, hold ? b.timing.msAt(end) : t, hold);
            }
            komgp.core.Chart c = b.build();
            komgp.format.TextChart.save(c, textDir.resolve("Neon Pulse [Hard].kmt"), audioName, "cover.png");
            Log.info("生成 " + textDir.resolve("Neon Pulse [Hard].kmt").getFileName() + " (明文示例)");
        }

        Files.deleteIfExists(wav);
        if (audio != wav) Files.deleteIfExists(audio);
        Files.deleteIfExists(tmp);
    }

    private static String extOf(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "wav";
    }

    private static void packMc(OutputStream out, Song s, String diffName, String level, boolean hard,
                               String audioName, byte[] audio, byte[] cover, byte[] bg) throws IOException {
        Map<String, Object> chart = buildChart(s, diffName, level, hard, audioName);
        byte[] json = Json.write(chart, true).getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            put(zos, "chart.json", json);
            put(zos, audioName, audio);
            put(zos, "cover.png", cover);
            put(zos, "bg.png", bg);
        }
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    // ------------------------------------------------------------------ 谱面

    private static Map<String, Object> buildChart(Song s, String diffName, String level, boolean hard,
                                                  String audioName) {
        Random rnd = new Random(s.seed * 31 + diffName.hashCode());
        List<Object> notes = new ArrayList<>();
        int bars = s.bars;
        int lastCol = -1;

        for (int bar = 0; bar < bars; bar++) {
            int section = (bar / 4) % 4;
            int[] steps;
            if (hard) {
                steps = switch (section) {
                    case 0 -> new int[]{0, 2, 4, 6, 8, 10, 12, 14};
                    case 1 -> new int[]{0, 2, 3, 4, 6, 8, 10, 11, 12, 14, 15};
                    case 2 -> new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
                    default -> new int[]{0, 2, 4, 6, 8, 9, 10, 12, 14};
                };
            } else {
                steps = switch (section) {
                    case 0 -> new int[]{0, 4, 8, 12};
                    case 1 -> new int[]{0, 2, 4, 6, 8, 10, 12, 14};
                    case 2 -> new int[]{0, 4, 8, 12};
                    default -> new int[]{0, 3, 6, 8, 11, 14};
                };
            }
            if (bar < 2) continue;

            int cursor = 0;
            for (int step : steps) {
                int col = ladder(cursor++, bar, rnd, lastCol, hard);
                lastCol = col;
                addNote(notes, bar, step, col);
                if (hard && section == 3 && step % 8 == 0) addNote(notes, bar, step, (col + 2) % 4);
            }
            if (bar % 4 == 2) {
                int col = rnd.nextInt(4);
                int len = hard ? 8 : 6;
                addHold(notes, bar, 0, col, len);
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("creator", "AutoDemo");
        meta.put("background", "bg.png");
        meta.put("cover", "cover.png");
        meta.put("version", diffName);
        meta.put("level", level);
        meta.put("mode", 0);

        Map<String, Object> song = new LinkedHashMap<>();
        song.put("title", s.title);
        song.put("titleorg", s.titleZh);
        song.put("artist", s.artist);
        song.put("file", audioName);
        meta.put("song", song);

        Map<String, Object> modeExt = new LinkedHashMap<>();
        modeExt.put("column", 4);
        modeExt.put("bar_begin", 2);
        meta.put("mode_ext", modeExt);

        Map<String, Object> time = new LinkedHashMap<>();
        time.put("beat", List.of(0, 0, 1));
        time.put("bpm", s.bpm);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("time", List.of(time));
        root.put("note", notes);
        return root;
    }

    /** 交替的「阶梯」选列：看起来像人写的谱面，而不是随机噪声。 */
    private static int ladder(int i, int bar, Random rnd, int lastCol, boolean hard) {
        int[][] shapes = {
                {0, 1, 2, 3}, {3, 2, 1, 0}, {0, 2, 1, 3}, {3, 1, 2, 0},
                {0, 1, 3, 2}, {2, 0, 1, 3}
        };
        int[] shape = shapes[(bar * 3 + (rnd.nextInt(2))) % shapes.length];
        int col = shape[i % shape.length];
        if (col == lastCol) col = (col + 1 + (hard ? 1 : 0)) % 4;
        return col;
    }

    /** 16 分步进 -> 绝对拍数（一小节 4 拍）。 */
    private static double beatOfStep(int bar, int step) {
        return bar * 4.0 + step / 4.0;
    }

    private static void addNote(List<Object> notes, int bar, int step, int col) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("beat", komgp.format.MalodyLoader.beatArray(beatOfStep(bar, step)));
        n.put("column", col);
        notes.add(n);
    }

    private static void addHold(List<Object> notes, int bar, int step, int col, int lenSteps) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("beat", komgp.format.MalodyLoader.beatArray(beatOfStep(bar, step)));
        n.put("endbeat", komgp.format.MalodyLoader.beatArray(beatOfStep(bar, step + lenSteps)));
        n.put("column", col);
        notes.add(n);
    }

    // ------------------------------------------------------------------ 音频合成

    private static final int SR = 44100;

    private static short[] synth(Song s) {
        double stepDur = 60.0 / s.bpm / 4;      // 十六分音符时长
        int totalSteps = s.bars * 16;
        int n = (int) ((totalSteps * stepDur + 2.0) * SR);
        double[] L = new double[n];
        double[] R = new double[n];

        double[] pentatonic = {0, 3, 5, 7, 10};

        for (int step = 0; step < totalSteps; step++) {
            double t = step * stepDur;
            int inBar = step % 16;
            int bar = step / 16;
            int barInCycle = bar % 4;

            if (inBar == 0 || inBar == 8) kick(L, R, t);
            if (inBar == 4 || inBar == 12) snare(L, R, t);
            if (inBar % 2 == 0) hat(L, R, t, inBar % 4 == 0 ? 0.16 : 0.09);

            // 贝斯：每半拍，根音
            if (inBar % 2 == 0) {
                int semi = s.chordRoots[barInCycle];
                double f = 110.0 * Math.pow(2, semi / 12.0);
                bass(L, R, t, f * (inBar % 8 == 0 ? 1.0 : 0.5), stepDur * 1.8);
            }

            // 主旋律：五声音阶琶音
            if (step % 2 == 0 && bar >= 2) {
                int idx = (step * 3 + bar) % pentatonic.length;
                int oct = ((step / 8) % 2 == 0) ? 1 : 2;
                double semi = pentatonic[idx] + (oct == 2 ? 12 : 0);
                double f = 440.0 * Math.pow(2, semi / 12.0);
                lead(L, R, t, f, stepDur * 1.5);
                // 每 4 拍补一个高音
                if (inBar == 0) lead(L, R, t, f * 2, stepDur * 3.0);
            }
        }

        // 归一化 + 软削波
        double peak = 1e-9;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.max(Math.abs(L[i]), Math.abs(R[i])));
        double gain = 0.82 / peak;
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            double l = Math.tanh(L[i] * gain * 1.2);
            double r = Math.tanh(R[i] * gain * 1.2);
            out[i * 2] = (short) (l * 32000);
            out[i * 2 + 1] = (short) (r * 32000);
        }
        return out;
    }

    private static void add(double[] L, double[] R, int i, double v, double pan) {
        if (i < 0 || i >= L.length) return;
        double l = v * (0.5 - pan * 0.5 + 0.5);
        double r = v * (0.5 + pan * 0.5 + 0.5);
        L[i] += l * 0.5;
        R[i] += r * 0.5;
    }

    private static void kick(double[] L, double[] R, double t) {
        int start = (int) (t * SR);
        int len = (int) (0.22 * SR);
        for (int i = 0; i < len; i++) {
            double tt = i / (double) SR;
            double f = 130 * Math.exp(-tt * 22) + 46;
            double env = Math.exp(-tt * 12);
            double v = Math.sin(2 * Math.PI * f * tt) * env * 0.9;
            v += (Math.random() * 2 - 1) * Math.exp(-tt * 200) * 0.12;
            add(L, R, start + i, v, 0);
        }
    }

    private static void snare(double[] L, double[] R, double t) {
        int start = (int) (t * SR);
        int len = (int) (0.18 * SR);
        Random rnd = new Random((long) (t * 1000));
        for (int i = 0; i < len; i++) {
            double tt = i / (double) SR;
            double env = Math.exp(-tt * 26);
            double noise = (rnd.nextDouble() * 2 - 1) * 0.55;
            double tone = Math.sin(2 * Math.PI * 190 * tt) * 0.3;
            add(L, R, start + i, (noise + tone) * env, 0.08);
        }
    }

    private static void hat(double[] L, double[] R, double t, double amp) {
        int start = (int) (t * SR);
        int len = (int) (0.045 * SR);
        Random rnd = new Random((long) (t * 7919) + 13);
        double prev = 0;
        for (int i = 0; i < len; i++) {
            double tt = i / (double) SR;
            double noise = rnd.nextDouble() * 2 - 1;
            double hp = noise - prev * 0.92;
            prev = noise;
            add(L, R, start + i, hp * Math.exp(-tt * 90) * amp, 0.3);
        }
    }

    private static void bass(double[] L, double[] R, double t, double f, double dur) {
        int start = (int) (t * SR);
        int len = (int) (Math.min(0.4, dur) * SR);
        for (int i = 0; i < len; i++) {
            double tt = i / (double) SR;
            double env = Math.min(1, tt * 90) * Math.exp(-tt * 6);
            double saw = 2 * ((tt * f) % 1.0) - 1;
            double v = (Math.sin(2 * Math.PI * f * tt) * 0.65 + saw * 0.25) * env * 0.5;
            add(L, R, start + i, v, -0.05);
        }
    }

    private static void lead(double[] L, double[] R, double t, double f, double dur) {
        int start = (int) (t * SR);
        int len = (int) (Math.min(0.5, dur) * SR);
        for (int i = 0; i < len; i++) {
            double tt = i / (double) SR;
            double env = Math.min(1, tt * 140) * Math.exp(-tt * 7);
            double sq = Math.signum(Math.sin(2 * Math.PI * f * tt));
            double v = (Math.sin(2 * Math.PI * f * tt) * 0.5 + sq * 0.18) * env * 0.28;
            add(L, R, start + i, v, -0.22);
        }
    }

    // ------------------------------------------------------------------ 文件

    private static boolean convert(Path in, Path out, String ext) {
        String codec = "ogg".equals(ext) ? "libvorbis" : "libmp3lame";
        try {
            Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error",
                    "-i", in.toString(), "-c:a", codec, "-q:a", "5", out.toString())
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(60, TimeUnit.SECONDS);
            return ok && p.exitValue() == 0 && Files.exists(out) && Files.size(out) > 1024;
        } catch (Exception e) {
            Log.debug("ffmpeg 转码失败: " + e.getMessage());
            return false;
        }
    }

    private static void writeWav(Path path, short[] pcm, int sr, int ch) throws IOException {
        int dataLen = pcm.length * 2;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(44 + dataLen);
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        out.writeBytes("RIFF");
        out.writeInt(Integer.reverseBytes(36 + dataLen));
        out.writeBytes("WAVEfmt ");
        out.writeInt(Integer.reverseBytes(16));
        out.writeShort(Short.reverseBytes((short) 1));
        out.writeShort(Short.reverseBytes((short) ch));
        out.writeInt(Integer.reverseBytes(sr));
        out.writeInt(Integer.reverseBytes(sr * ch * 2));
        out.writeShort(Short.reverseBytes((short) (ch * 2)));
        out.writeShort(Short.reverseBytes((short) 16));
        out.writeBytes("data");
        out.writeInt(Integer.reverseBytes(dataLen));
        for (short s : pcm) out.writeShort(Short.reverseBytes(s));
        out.flush();
        Files.write(path, bos.toByteArray());
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 曲绘

    private static BufferedImage drawCover(Song s) {
        int w = 600;
        int h = 600;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Color top = Color.getHSBColor(s.hue, 0.55f, 0.30f);
        Color bottom = Color.getHSBColor((s.hue + 0.55f) % 1f, 0.75f, 0.10f);
        g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
        g.fillRect(0, 0, w, h);

        // 太阳
        int cx = w / 2;
        int cy = (int) (h * 0.42);
        for (int r = 220; r > 0; r -= 8) {
            float a = 0.012f * (220 - r);
            g.setColor(new Color(255, 220, 170, (int) Math.min(120, 255 * a * 6)));
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
        }
        g.setColor(new Color(255, 236, 200, 220));
        g.fillOval(cx - 92, cy - 92, 184, 184);
        g.setColor(top);
        for (int i = 0; i < 6; i++) {
            g.fillRect(cx - 100, cy - 20 + i * 22, 200, 6 + i * 2);
        }

        // 透视网格
        g.setColor(new Color(120, 220, 255, 70));
        g.setStroke(new BasicStroke(1.4f));
        for (int i = -8; i <= 8; i++) {
            g.drawLine(cx + i * 18, cy + 40, cx + i * 190, h);
        }
        for (int i = 0; i < 9; i++) {
            int y = cy + 40 + (int) (Math.pow(i / 8.0, 1.8) * (h - cy - 40));
            g.drawLine(0, y, w, y);
        }

        // 标题
        g.setColor(new Color(0, 0, 0, 110));
        g.fillRect(0, h - 168, w, 168);
        g.setColor(new Color(255, 255, 255, 235));
        g.setFont(Fonts.bold(46));
        g.drawString(s.title, 36, h - 104);
        g.setFont(Fonts.plain(24));
        g.setColor(new Color(160, 230, 255, 230));
        g.drawString(s.titleZh + "   ·   " + s.artist, 36, h - 62);
        g.setFont(Fonts.of(Font.PLAIN, 18));
        g.setColor(new Color(255, 255, 255, 120));
        g.drawString(String.format("%.0f BPM  ·  MALODY 4K", s.bpm), 36, h - 30);

        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(16, 16, w - 32, h - 32);
        g.dispose();
        return img;
    }

    private static BufferedImage drawBackdrop(Song s, BufferedImage cover) {
        int w = 1280;
        int h = 720;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        AffineTransform old = g.getTransform();
        g.translate(w / 2.0, h / 2.0);
        g.rotate(Math.toRadians(-6));
        g.drawImage(cover, -520, -520, 1040, 1040, null);
        g.setTransform(old);
        g.setPaint(new GradientPaint(0, 0, new Color(6, 8, 18, 200), w, h, new Color(4, 6, 14, 235)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }
}
