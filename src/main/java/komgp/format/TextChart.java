package komgp.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import komgp.core.Chart;
import komgp.core.Timing;
import komgp.util.Log;

/**
 * 明文谱面格式（扩展名 {@code .kmt}，KOMGP Music Text）。
 *
 * <p>设计目标：人能直接读、能手写、能用 diff 管版本、出错时能一眼看出问题。
 * 整个文件就是「魔数 + key value 行」，不需要 JSON 解析器。
 *
 * <pre>
 * KOMGP CHART 1
 * title 霓虹脉冲
 * artist DSH Audio Lab
 * difficulty Hard
 * level 12
 * audio audio.ogg
 * cover cover.png
 * bpm 150
 * timing 0 150            # 从第 0 拍开始 150 BPM
 * timing 192 180          # 第 192 拍变速
 * note 1200 0             # 1200ms 第 0 列，单点
 * note 1600 2 800         # 1600ms 第 2 列，按住 800ms
 * </pre>
 *
 * <p>识别方式见 {@link ChartIO}：靠首行魔数，而不是扩展名。
 */
public final class TextChart {

    /** 首行魔数（用于内容嗅探）。 */
    public static final String MAGIC = "KOMGP CHART";
    public static final int VERSION = 1;
    public static final String EXT = ".kmt";

    private TextChart() {}

    // ------------------------------------------------------------------ 识别

    public static boolean looksLikeText(String head) {
        if (head == null) return false;
        String t = head.stripLeading();
        if (t.startsWith("\uFEFF")) t = t.substring(1).stripLeading();
        return t.regionMatches(true, 0, MAGIC, 0, MAGIC.length());
    }

    public static boolean looksLikeText(byte[] data) {
        if (data == null || data.length < MAGIC.length()) return false;
        int len = Math.min(data.length, 512);
        return looksLikeText(new String(data, 0, len, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 读取

    public static ChartPackage load(Path path, boolean withMedia) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Chart chart = parse(text, path);
            if (chart == null || chart.noteCount() == 0) {
                return ChartPackage.failed(path, "text", "明文谱面里没有音符");
            }
            byte[] audio = null;
            byte[] cover = null;
            byte[] bg = null;
            if (withMedia) {
                audio = sibling(path, chart.audioEntry);
                cover = sibling(path, chart.coverEntry);
                bg = sibling(path, chart.backgroundEntry);
                if (cover == null) cover = bg;
                if (bg == null) bg = cover;
            }
            String note = withMedia && audio == null ? "缺少音频" : null;
            return new ChartPackage(path, List.of(new ChartEntry(chart, audio, chart.audioEntry,
                    cover, bg, note)), null, "text");
        } catch (IOException e) {
            return ChartPackage.failed(path, "text", "读取失败: " + e.getMessage());
        }
    }

    private static byte[] sibling(Path chart, String name) {
        if (name == null || name.isBlank()) return null;
        try {
            Path dir = chart.toAbsolutePath().getParent();
            Path f = dir == null ? Path.of(name) : dir.resolve(name);
            if (!Files.isRegularFile(f)) {
                // 大小写不敏感兜底
                if (dir != null) {
                    try (var s = Files.list(dir)) {
                        var hit = s.filter(x -> x.getFileName().toString().equalsIgnoreCase(name))
                                .findFirst();
                        if (hit.isPresent()) f = hit.get();
                    }
                }
            }
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 解析文本；解析失败抛 {@link IllegalArgumentException}（由 load 转成错误包）。 */
    public static Chart parse(String text, Path source) {
        List<Double> beats = new ArrayList<>();
        List<Double> bpms = new ArrayList<>();
        List<double[]> notes = new ArrayList<>(); // {timeMs, col, lenMs}
        Chart.Builder b = Chart.builder();
        b.sourcePath = source == null ? "" : source.toString();
        b.container = "";

        String title = null;
        String titleOrg = null;
        String artist = null;
        String creator = "";
        String difficulty = "";
        String level = "";
        String audio = null;
        String cover = null;
        String bg = null;
        double defaultBpm = 0;
        int lineNo = 0;
        int maxCol = 3;

        for (String rawLine : text.split("\\R")) {
            lineNo++;
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue;
            int sp = line.indexOf(' ');
            String key = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT);
            String rest = sp < 0 ? "" : line.substring(sp + 1).strip();
            try {
                switch (key) {
                    case "komgp" -> { /* 魔数行：KOMGP CHART 1 */ }
                    case "title" -> title = rest;
                    case "titleorg" -> titleOrg = rest;
                    case "artist" -> artist = rest;
                    case "creator" -> creator = rest;
                    case "difficulty" -> difficulty = rest;
                    case "level" -> level = rest;
                    case "audio" -> audio = rest;
                    case "cover" -> cover = rest;
                    case "background", "bg" -> bg = rest;
                    case "bpm" -> defaultBpm = parseNum(rest, 0);
                    case "timing" -> {
                        String[] f = rest.split("\\s+");
                        if (f.length >= 2) {
                            beats.add(parseNum(f[0], 0));
                            bpms.add(parseNum(f[1], 120));
                        }
                    }
                    case "note", "hold", "tap" -> {
                        String[] f = rest.split("\\s+");
                        if (f.length < 2) break;
                        double t = parseNum(f[0], Double.NaN);
                        int col = (int) parseNum(f[1], 0);
                        if (Double.isNaN(t) || t < 0) break;
                        double len = f.length >= 3 ? parseNum(f[2], 0) : 0;
                        notes.add(new double[]{t, col, Math.max(0, len)});
                        maxCol = Math.max(maxCol, col);
                    }
                    default -> { /* 未知字段：向前兼容，直接忽略 */ }
                }
            } catch (RuntimeException e) {
                Log.debug("明文谱面第 " + lineNo + " 行解析失败: " + rawLine);
            }
        }

        if (beats.isEmpty()) {
            beats.add(0.0);
            bpms.add(defaultBpm > 0 ? defaultBpm : 120.0);
        }
        double maxBeat = 0;
        for (double[] n : notes) {
            maxBeat = Math.max(maxBeat, beats.get(0) + n[0] / 60000.0 * bpms.get(0));
        }
        Timing timing = Timing.of(toArray(beats), toArray(bpms), maxBeat + 8);

        int columns = Math.max(4, Math.min(10, maxCol + 1));
        b.columns = columns;
        b.timing = timing;
        b.title = title == null || title.isBlank() ? stem(source) : title;
        b.titleOrig = titleOrg;
        b.artist = artist == null || artist.isBlank() ? "Unknown" : artist;
        b.creator = creator;
        b.difficulty = difficulty;
        b.level = level;
        b.audioEntry = audio;
        b.coverEntry = cover;
        b.backgroundEntry = bg != null ? bg : cover;
        b.warning = columns != 4 ? "非 4K 谱面（" + columns + "K）" : "";
        for (double[] n : notes) {
            b.add((int) n[1], n[0], n[0] + n[2], n[2] > 0);
        }
        return b.build();
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static double parseNum(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stem(Path p) {
        if (p == null) return "Untitled";
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ------------------------------------------------------------------ 写出

    /**
     * 把谱面序列化成明文文本。
     *
     * @param chart  谱面
     * @param audio  音频文件名（相对谱面所在目录）
     * @param cover  曲绘文件名，可空
     */
    public static String write(Chart chart, String audio, String cover) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(MAGIC).append(' ').append(VERSION).append('\n');
        sb.append("# 由 KOMGP 谱面编辑器生成，纯文本可直接手改\n");
        sb.append("title ").append(chart.title).append('\n');
        if (chart.titleOrig != null && !chart.titleOrig.isBlank()) {
            sb.append("titleorg ").append(chart.titleOrig).append('\n');
        }
        sb.append("artist ").append(chart.artist).append('\n');
        if (chart.creator != null && !chart.creator.isBlank()) {
            sb.append("creator ").append(chart.creator).append('\n');
        }
        if (chart.difficulty != null && !chart.difficulty.isBlank()) {
            sb.append("difficulty ").append(chart.difficulty).append('\n');
        }
        if (chart.level != null && !chart.level.isBlank()) {
            sb.append("level ").append(chart.level).append('\n');
        }
        if (audio != null) sb.append("audio ").append(audio).append('\n');
        if (cover != null) sb.append("cover ").append(cover).append('\n');
        sb.append('\n');

        Timing t = chart.timing;
        for (int i = 0; i < t.changeCount(); i++) {
            sb.append(String.format(Locale.ROOT, "timing %.4f %.4f%n", t.beatAt(i), t.bpmAt(i)));
        }
        sb.append('\n');
        sb.append("# 时间(ms) 列号 [按住时长(ms)]\n");
        int n = chart.noteCount();
        for (int i = 0; i < n; i++) {
            double time = chart.time[i];
            int col = chart.column[i];
            if (chart.hold[i]) {
                long len = Math.round(chart.endTime[i] - time);
                sb.append(String.format(Locale.ROOT, "note %.0f %d %d%n", time, col, len));
            } else {
                sb.append(String.format(Locale.ROOT, "note %.0f %d%n", time, col));
            }
        }
        return sb.toString();
    }

    /** 写文件并返回路径。 */
    public static Path save(Chart chart, Path target, String audio, String cover) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(target, write(chart, audio, cover), StandardCharsets.UTF_8);
        return target;
    }
}
