package komgp.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import komgp.core.Chart;
import komgp.core.Timing;
import komgp.util.Log;

/**
 * Malody 包（{@code .mcz}）加载器 —— 只支持当前主流版本。
 *
 * <p>包结构：
 * <pre>
 *   xxx.mcz
 *   ├── chart.mc         纯 JSON 文本（不是 zip！）
 *   └── song.ogg         音频 / 曲绘，和 .mc 平级放在包根
 * </pre>
 *
 * <p>谱面字段：
 * <ul>
 *   <li>{@code meta.song.{title,artist,file}}，{@code meta.{version,level,creator,cover,background}}</li>
 *   <li>{@code meta.mode_ext.column} 键数</li>
 *   <li>{@code time[]} = {beat:[小节,分子,分母], bpm}</li>
 *   <li>{@code note[]} = {beat, column, endbeat?} 长条靠 endbeat</li>
 *   <li>{@code note[]} 里 {@code {"type":1,"sound":"x.ogg","offset":478}} 是音源事件：
 *       不是音符，offset 表示「音频晚 478ms 开始」，记到 {@link Chart#audioDelayMs}，
 *       <b>不</b>改动音符时间轴。</li>
 * </ul>
 *
 * <p>旧版「.mc 是 zip、音符用 x、长条放 extra」的格式不再支持，
 * 遇到时会给出明确提示而不是静默失败。
 */
public final class MalodyLoader {

    private MalodyLoader() {}

    private static final String[] AUDIO_EXT = {".ogg", ".mp3", ".wav", ".m4a", ".flac", ".aac", ".opus"};
    private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".bmp"};

    // ------------------------------------------------------------------ 入口

    public static ChartPackage load(Path path, boolean withMedia) {
        if (!isZipFile(path)) {
            return ChartPackage.failed(path, "malody",
                    "不是有效的 .mcz（ZIP）包");
        }
        try (ZipFile zf = new ZipFile(path.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) names.add(e.getName());
            }
            List<ChartEntry> out = new ArrayList<>(2);
            int legacy = 0;
            for (String name : names) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".mc") && !lower.endsWith(".json")) continue;
                byte[] data = read(zf, name);
                if (data == null) continue;
                if (isZip(data)) {
                    legacy++;   // 旧版：.mc 本身是个 zip
                    continue;
                }
                if (!looksLikeChart(data)) continue;
                parse(data, name, zf, path, withMedia, out);
            }
            if (!out.isEmpty()) {
                return new ChartPackage(path, out, null, "malody");
            }
            if (legacy > 0) {
                return ChartPackage.failed(path, "malody",
                        "这是旧版 Malody 包（.mc 是 ZIP）。请用 Malody 重新导出为 .mcz");
            }
            return ChartPackage.failed(path, "malody", "包内没有找到谱面 JSON");
        } catch (IOException e) {
            Log.error("读取 .mcz 失败: " + path, e);
            return ChartPackage.failed(path, "malody", "读取失败: " + e.getMessage());
        }
    }

    /** 只读某个媒体条目（曲库懒加载封面用）。container 保留参数但不使用。 */
    public static byte[] readMedia(Path path, String container, String entry) {
        if (entry == null || entry.isBlank()) return null;
        try (ZipFile zf = new ZipFile(path.toFile())) {
            byte[] b = read(zf, entry);
            if (b != null) return b;
            // 大小写/转义兜底
            String want = uriDecode(entry).toLowerCase(Locale.ROOT);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = uriDecode(e.getName()).toLowerCase(Locale.ROOT);
                if (n.equals(want) || n.endsWith("/" + want)) return read(zf, e.getName());
            }
        } catch (Exception e) {
            Log.debug("读取媒体失败 " + entry + ": " + e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------ 解析

    private static boolean parse(byte[] data, String entryName, ZipFile zf, Path path,
                                 boolean withMedia, List<ChartEntry> out) {
        try {
            Object root = Json.parse(new String(data, StandardCharsets.UTF_8));
            Map<String, Object> r = Json.asObj(root);
            if (r == null) return false;

            Map<String, Object> meta = orEmpty(Json.asObj(r.get("meta")));
            Map<String, Object> song = orEmpty(Json.asObj(meta.get("song")));
            Map<String, Object> modeExt = orEmpty(Json.asObj(meta.get("mode_ext")));

            Chart.Builder b = Chart.builder();
            b.title = firstNonBlank(Json.str(song.get("title"), null),
                    Json.str(meta.get("title"), null), stem(path));
            b.titleOrig = firstNonBlank(Json.str(song.get("titleorg"), null), null);
            b.artist = firstNonBlank(Json.str(song.get("artist"), null), "Unknown");
            b.artistOrig = firstNonBlank(Json.str(song.get("artistorg"), null), null);
            b.creator = orEmpty(Json.str(meta.get("creator"), null));
            b.difficulty = orEmpty(Json.str(meta.get("version"), null));
            b.level = orEmpty(Json.str(meta.get("level"), null));
            b.sourcePath = path == null ? "" : path.toString();
            b.container = "";

            // ---- 速度点
            List<double[]> bpmPoints = new ArrayList<>();
            String cueSound = null;
            double cueOffset = 0;
            for (Object o : orEmptyList(Json.asArr(r.get("time")))) {
                Map<String, Object> e = Json.asObj(o);
                if (e == null) continue;
                double bpm = Json.num(e.get("bpm"), 0);
                if (bpm > 0) {
                    double beat = beatOf(e.get("beat"));
                    if (!Double.isNaN(beat)) bpmPoints.add(new double[]{beat, bpm});
                }
            }

            // ---- 音符
            List<Object> notes = Json.asArr(r.get("note"));
            double maxBeat = 0;
            int maxCol = -1;
            int dropped = 0;
            List<double[]> raw = new ArrayList<>();
            if (notes != null) {
                for (Object o : notes) {
                    Map<String, Object> m = Json.asObj(o);
                    if (m == null) continue;
                    Object colObj = firstOf(m, "column", "x");
                    if (colObj == null) {
                        // 音源事件
                        String snd = Json.str(m.get("sound"), null);
                        if (snd != null && cueSound == null) cueSound = snd;
                        if (m.get("offset") != null) cueOffset = Json.num(m.get("offset"), 0);
                        continue;
                    }
                    double beat = beatOf(m.get("beat"));
                    if (Double.isNaN(beat)) { dropped++; continue; }
                    int col = (int) Json.num(colObj, -1);
                    if (col < 0) { dropped++; continue; }
                    double end = beatOf(firstOf(m, "endbeat", "endBeat"));
                    boolean hold = !Double.isNaN(end) && end > beat;
                    maxBeat = Math.max(maxBeat, Math.max(beat, hold ? end : beat));
                    maxCol = Math.max(maxCol, col);
                    raw.add(new double[]{col, beat, hold ? end : Double.NaN});
                }
            }
            if (raw.isEmpty()) return false;

            if (bpmPoints.isEmpty()) bpmPoints.add(new double[]{0, 120});
            bpmPoints.sort((x, y) -> Double.compare(x[0], y[0]));
            Timing timing = Timing.of(
                    bpmPoints.stream().mapToDouble(p -> p[0]).toArray(),
                    bpmPoints.stream().mapToDouble(p -> p[1]).toArray(),
                    maxBeat + 8);
            b.timing = timing;

            int columns = (int) Json.num(modeExt.get("column"), 0);
            if (columns <= 0) columns = maxCol + 1;
            if (columns <= 0) columns = 4;
            b.columns = Math.min(columns, 10);
            b.rawColumns = b.columns;
            b.rawMode = (int) Json.num(meta.get("mode"), 0);
            b.scrollHint = (int) Json.num(modeExt.get("speed"), 0);
            b.audioDelayMs = Math.max(0, cueOffset);

            for (double[] n : raw) {
                int col = (int) n[0];
                if (col < 0 || col >= b.columns) { dropped++; continue; }
                double t = timing.msAt(n[1]);
                boolean hold = !Double.isNaN(n[2]);
                b.add(col, t, hold ? timing.msAt(n[2]) : t, hold);
            }

            // ---- 媒体
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) names.add(e.getName());
            }
            String audioReq = firstNonBlank(Json.str(song.get("file"), null),
                    Json.str(meta.get("audio"), null), cueSound, null);
            String coverReq = firstNonBlank(Json.str(meta.get("cover"), null), null);
            String bgReq = firstNonBlank(Json.str(meta.get("background"), null), null);

            String audioName = findEntry(names, audioReq, AUDIO_EXT, null);
            String coverName = findEntry(names, coverReq, IMAGE_EXT, "cover");
            String bgName = findEntry(names, bgReq, IMAGE_EXT, "bg");
            if (coverName == null) coverName = bgName;
            if (bgName == null) bgName = coverName;

            byte[] audio = withMedia && audioName != null ? read(zf, audioName) : null;
            byte[] cover = withMedia && coverName != null ? read(zf, coverName) : null;
            byte[] bg = withMedia && bgName != null ? read(zf, bgName) : null;

            b.audioEntry = audioName;
            b.coverEntry = coverName;
            b.backgroundEntry = bgName;

            StringBuilder warn = new StringBuilder();
            if (dropped > 0) warn.append(dropped).append(" 个非法音符被忽略; ");
            if (b.columns != 4) warn.append("非 4K（").append(b.columns).append("K）; ");
            if (withMedia && audio == null) warn.append("缺少音频; ");
            if (cueOffset > 0) warn.append(String.format("谱面音频延迟 %.0fms; ", cueOffset));
            if (warn.length() > 2) warn.setLength(warn.length() - 2);
            b.warning = warn.toString();

            Chart chart = b.build();
            if (chart.noteCount() == 0) return false;
            out.add(new ChartEntry(chart, audio, audioName, cover, bg,
                    withMedia && audio == null ? "缺少音频" : null));
            return true;
        } catch (RuntimeException e) {
            Log.debug("解析 " + entryName + " 失败: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static boolean isZipFile(Path p) {
        try (InputStream in = java.nio.file.Files.newInputStream(p)) {
            byte[] h = new byte[4];
            if (in.read(h) != 4) return false;
            return h[0] == 'P' && h[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isZip(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K'
                && (d[2] == 3 || d[2] == 5) && (d[3] == 4 || d[3] == 6);
    }

    private static boolean looksLikeChart(byte[] data) {
        if (data.length < 16) return false;
        String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.UTF_8);
        return (head.contains("\"note\"") || head.contains("\"time\""))
                && (head.contains("\"meta\"") || head.contains("\"bpm\""));
    }

    private static byte[] read(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return readAll(in, 256L << 20);
        } catch (IOException ex) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 16);
        int r;
        long total = 0;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
            total += r;
            if (total > limit) throw new IOException("条目过大");
        }
        return bos.toByteArray();
    }

    /** 在包内找媒体：先按声明名（含 %20 解码），再按扩展名兜底。 */
    private static String findEntry(List<String> names, String requested, String[] exts, String hint) {
        if (names.isEmpty()) return null;
        if (requested != null && !requested.isBlank()) {
            String req = uriDecode(requested).replace('\\', '/');
            while (req.startsWith("./")) req = req.substring(2);
            int slash = req.lastIndexOf('/');
            String reqBase = slash >= 0 ? req.substring(slash + 1) : req;
            String baseHit = null;
            String noExtHit = null;
            for (String name : names) {
                String n = uriDecode(name).replace('\\', '/');
                String nBase = n.substring(n.lastIndexOf('/') + 1);
                if (n.equalsIgnoreCase(req)) {
                    if (hasExt(n, exts)) return name;
                }
                if (baseHit == null && nBase.equalsIgnoreCase(reqBase) && hasExt(n, exts)) baseHit = name;
                if (noExtHit == null && stripExt(nBase).equalsIgnoreCase(stripExt(reqBase))
                        && hasExt(n, exts)) noExtHit = name;
            }
            if (baseHit != null) return baseHit;
            if (noExtHit != null) return noExtHit;
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String name : names) {
            if (!hasExt(name, exts)) continue;
            String low = uriDecode(name).toLowerCase(Locale.ROOT);
            int score = 0;
            if (hint != null) {
                if (low.contains(hint)) score += 20;
                if (hint.equals("cover") && (low.contains("jacket") || low.contains("bg"))) score += 6;
            }
            score -= (int) low.chars().filter(c -> c == '/').count() * 2;
            if (low.contains("preview")) score -= 30;
            if (score > bestScore) { bestScore = score; best = name; }
        }
        return best;
    }

    private static boolean hasExt(String name, String[] exts) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String e : exts) if (low.endsWith(e)) return true;
        return false;
    }

    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String uriDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** beat 数组 -> 绝对拍数：[小节, 分子, 分母] => (小节 + 分子/分母) * 4。 */
    public static double beatOf(Object o) {
        List<Object> l = Json.asArr(o);
        if (l != null) {
            if (l.size() >= 3) {
                double bar = Json.num(l.get(0), 0);
                double num = Json.num(l.get(1), 0);
                double den = Json.num(l.get(2), 0);
                if (den <= 0) den = 1;
                return (bar + num / den) * 4.0;
            }
            if (l.size() == 2) {
                return Json.num(l.get(0), 0) * 4.0 + Json.num(l.get(1), 0);
            }
            if (l.size() == 1) return Json.num(l.get(0), 0) * 4.0;
        }
        if (o instanceof Number n) return n.doubleValue();
        return Double.NaN;
    }

    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.containsKey(k)) return m.get(k);
        return null;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? new LinkedHashMap<>() : m;
    }

    private static List<Object> orEmptyList(List<Object> l) {
        return l == null ? List.of() : l;
    }

    private static String stem(Path p) {
        if (p == null) return "Untitled";
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
