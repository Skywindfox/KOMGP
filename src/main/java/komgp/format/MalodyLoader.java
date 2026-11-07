package komgp.format;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import komgp.core.Chart;
import komgp.core.Timing;
import komgp.util.Log;

/**
 * Malody 谱面加载器。
 *
 * <p>Malody 的谱面文件格式并不统一，实际流通的有好几代：
 * <ul>
 *   <li>{@code .mc}：ZIP 包，里面有 {@code chart.json} + 音频 + 曲绘；</li>
 *   <li>{@code .mcz}：ZIP 包里套着若干 {@code .mc} 或多个 {@code chart.json}；</li>
 *   <li>裸 {@code .json}：只有谱面数据，音频放在同目录；</li>
 *   <li>不同版本的字段名略有差异（{@code endbeat}/{@code endBeat}、
 *       {@code note}/{@code extra}、字符串/数字 {@code type}）。</li>
 * </ul>
 *
 * <p>这里统一按「先按 ZIP 打开 → 收集所有看起来像谱面的 JSON → 逐个宽容解析」处理，
 * 并维护一份友好的降级说明（{@link Chart#warning}），而不是直接抛异常让玩家一脸懵。
 */
public final class MalodyLoader {

    private MalodyLoader() {}

    // ------------------------------------------------------------------ 对外入口

    public static ChartPackage load(Path path, boolean withMedia) {
        try {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            List<ChartEntry> out = new ArrayList<>(2);
            String err;
            if (lower.endsWith(".json")) {
                byte[] raw = Files.readAllBytes(path);
                Map<String, byte[]> extra = siblingMedia(path.getParent());
                err = parseBundle(new BytesSource(extra), null, "", raw, path, withMedia, out);
            } else if (lower.endsWith(".mc") || lower.endsWith(".mcz") || lower.endsWith(".zip")) {
                try (ZipFile zf = new ZipFile(path.toFile())) {
                    err = parseZipFile(zf, path, withMedia, out);
                }
            } else {
                err = "不认识的文件类型";
            }
            if (out.isEmpty()) {
                return ChartPackage.failed(path, "malody", err == null ? "包内没有找到谱面数据" : err);
            }
            return new ChartPackage(path, out, null, "malody");
        } catch (Exception e) {
            Log.error("加载失败: " + path, e);
            return ChartPackage.failed(path, "malody", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ ZIP 处理

    private static String parseZipFile(ZipFile zf, Path path, boolean withMedia, List<ChartEntry> out) {
        List<String> names = new ArrayList<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            names.add(e.getName());
            sizes.put(e.getName(), e.getSize());
        }
        Src src = new Src() {
            @Override
            public List<String> names() { return names; }

            @Override
            public byte[] read(String name) {
                ZipEntry e = zf.getEntry(name);
                if (e == null) return null;
                try (InputStream in = zf.getInputStream(e)) {
                    return readAll(in, 128L << 20);
                } catch (IOException ex) {
                    return null;
                }
            }

            @Override
            public Src parent() { return null; }
        };
        return parseBundle(src, zf.getName(), "", null, path, withMedia, out);
    }

    private interface Src {
        List<String> names();

        byte[] read(String name);

        /** 外层容器（.mcz 里套 .mc 时，媒体往往在外层）。 */
        Src parent();
    }

    /** 沿父链读取：内层找不到就往外层找。 */
    private static byte[] readAny(Src src, String name) {
        for (Src cur = src; cur != null; cur = cur.parent()) {
            byte[] b = cur.read(name);
            if (b != null) return b;
        }
        return null;
    }

    /** URL 解码（媒体名里常见 %20）。 */
    private static String uriDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** 把一段 byte[] 当作 ZIP 再解一层（.mcz 里套 .mc）。 */
    private static final class BytesSource implements Src {
        private final Map<String, byte[]> entries;
        private final List<String> names;
        private final Src parent;

        BytesSource(Map<String, byte[]> entries) {
            this(entries, null);
        }

        BytesSource(Map<String, byte[]> entries, Src parent) {
            this.entries = entries;
            this.names = new ArrayList<>(entries.keySet());
            this.parent = parent;
        }

        @Override
        public List<String> names() { return names; }

        @Override
        public byte[] read(String name) { return entries.get(name); }

        @Override
        public Src parent() { return parent; }
    }

    private static String parseBundle(Src src, String zipName, String container, byte[] directJson,
                                      Path path, boolean withMedia, List<ChartEntry> out) {
        List<String> names = src.names();

        // A. ZIP 里嵌套的 .mc（每层再递归）
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".mc") && !lower.endsWith(".mcz")) continue;
            byte[] data = src.read(name);
            if (data == null || data.length < 4) continue;
            if (isZip(data)) {
                mapZip(data, name, withMedia, path, out, src);
            } else if (looksLikeJsonChart(data)) {
                // Malody V：.mc 可能是纯 JSON 文本，媒体在外层 .mcz 里
                parseOne(data, src, name, path, "", withMedia, out);
            }
        }

        // B. 看起来像谱面 JSON 的条目
        int found = 0;
        if (directJson != null) {
            parseOne(directJson, src, null, path, container, withMedia, out);
            found++;
        } else {
            List<String> candidates = new ArrayList<>();
            Map<String, byte[]> cache = new LinkedHashMap<>();
            for (String name : names) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".json")) continue;
                byte[] data = src.read(name);
                if (data == null || data.length > (64L << 20)) continue;
                if (!looksLikeChart(data)) continue;
                candidates.add(name);
                cache.put(name, data);
            }
            // chart.json 优先，其余按名字排序，保证顺序稳定
            candidates.sort((a, b) -> {
                boolean ca = a.toLowerCase(Locale.ROOT).endsWith("chart.json");
                boolean cb = b.toLowerCase(Locale.ROOT).endsWith("chart.json");
                if (ca != cb) return ca ? -1 : 1;
                return a.compareTo(b);
            });
            for (String name : candidates) {
                if (parseOne(cache.get(name), src, name, path, container, withMedia, out)) found++;
            }
            cache.clear();
        }

        if (found == 0) {
            return "没有在 " + (zipName == null ? path.getFileName() : zipName) + " 里找到谱面 JSON";
        }
        return null;
    }

    private static void mapZip(byte[] data, String innerName, boolean withMedia, Path path,
                               List<ChartEntry> out, Src outer) {
        Map<String, byte[]> entries = unzip(data, 128L << 20);
        if (entries.isEmpty()) return;
        BytesSource src = new BytesSource(entries, outer);
        parseBundle(src, innerName, innerName, null, path, withMedia, out);
    }

    private static boolean isZip(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K'
                && (d[2] == 3 || d[2] == 5) && (d[3] == 4 || d[3] == 6);
    }

    /** Malody V 的 .mc 是纯 JSON：{"meta":...,"time":...,"note":...} */
    private static boolean looksLikeJsonChart(byte[] d) {
        int i = 0;
        if (d.length > 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB) i = 3; // BOM
        while (i < d.length && (d[i] == ' ' || d[i] == '\n' || d[i] == '\r' || d[i] == '\t')) i++;
        if (i >= d.length || d[i] != '{') return false;
        return looksLikeChart(d);
    }

    private static boolean looksLikeChart(byte[] data) {
        if (data.length < 16) return false;
        String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.UTF_8);
        return (head.contains("\"note\"") || head.contains("\"time\""))
                && (head.contains("\"meta\"") || head.contains("\"bpm\""));
    }

    private static boolean parseOne(byte[] data, Src src, String entryName, Path path, String container,
                                    boolean withMedia, List<ChartEntry> out) {
        try {
            Object root = Json.parse(new String(data, StandardCharsets.UTF_8));
            Map<String, Object> rootObj = Json.asObj(root);
            if (rootObj == null) return false;
            return buildChart(rootObj, src, path, container, withMedia, out);
        } catch (RuntimeException e) {
            Log.debug("解析 " + entryName + " 失败: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 单张谱面

    private static boolean buildChart(Map<String, Object> root, Src src, Path path, String container,
                                      boolean withMedia, List<ChartEntry> out) {
        String entryName = path == null ? "" : path.getFileName().toString();
        Map<String, Object> meta = Json.asObj(root.get("meta"));
        if (meta == null) meta = Map.of();
        Map<String, Object> song = Json.asObj(meta.get("song"));
        if (song == null) song = Map.of();
        Map<String, Object> modeExt = Json.asObj(meta.get("mode_ext"));
        if (modeExt == null) modeExt = Map.of();

        String title = firstNonBlank(Json.str(song.get("title"), null),
                Json.str(meta.get("title"), null), fileStem(path));
        String titleOrig = firstNonBlank(Json.str(song.get("titleorg"), null),
                Json.str(meta.get("titleorg"), null), null);
        String artist = firstNonBlank(Json.str(song.get("artist"), null),
                Json.str(meta.get("artist"), null), "Unknown");
        String artistOrig = firstNonBlank(Json.str(song.get("artistorg"), null),
                Json.str(meta.get("artistorg"), null), null);

        int rawCols = (int) Math.round(numOf(modeExt.get("column"), numOf(meta.get("column"), 0)));
        int rawMode = (int) Math.round(numOf(meta.get("mode"), 0));

        // ---- 时间轴
        List<Object> timeList = Json.asArr(root.get("time"));
        if (timeList == null) timeList = Json.asArr(meta.get("time"));
        List<double[]> bpmPoints = new ArrayList<>();
        String soundCue = null;
        if (timeList != null) {
            for (Object o : timeList) {
                Map<String, Object> e = Json.asObj(o);
                if (e == null) continue;
                double bpm = numOf(e.get("bpm"), 0);
                String snd = Json.str(e.get("sound"), null);
                if (bpm > 0) {
                    double b = beatOf(e.get("beat"));
                    if (!Double.isNaN(b)) bpmPoints.add(new double[]{b, bpm});
                } else if (snd != null && soundCue == null) {
                    soundCue = snd;
                }
            }
        }
        if (bpmPoints.isEmpty()) {
            bpmPoints.add(new double[]{0, 120});
        }
        bpmPoints.sort((a, b) -> Double.compare(a[0], b[0]));

        // ---- 音符
        List<Object> notes = Json.asArr(root.get("note"));
        List<Object> extras = Json.asArr(root.get("extra"));

        // 先收集原始 beat，才能确定 maxBeat（用于生成小节线）
        double maxBeat = 0;
        List<double[]> raw = new ArrayList<>(); // {col, startBeat, endBeat or NaN, hold?}
        int dropped = 0;
        int maxColSeen = -1;

        double cueOffsetMs = 0;
        String cueSound = null;
        if (notes != null) {
            for (Object o : notes) {
                Map<String, Object> m = Json.asObj(o);
                if (m != null && isSoundCue(m)) {
                    String snd = Json.str(m.get("sound"), null);
                    if (snd != null && cueSound == null) cueSound = snd;
                    if (m.get("offset") != null) cueOffsetMs = numOf(m.get("offset"), 0);
                    continue;
                }
                double[] r = readNote(o);
                if (r == null) continue;
                maxColSeen = Math.max(maxColSeen, (int) r[0]);
                maxBeat = Math.max(maxBeat, Math.max(r[1], Double.isNaN(r[2]) ? r[1] : r[2]));
                raw.add(r);
            }
        }
        if (extras != null) {
            for (Object o : extras) {
                Map<String, Object> e = Json.asObj(o);
                if (e == null) continue;
                double b = beatOf(e.get("beat"));
                if (Double.isNaN(b)) continue;
                double end = beatOf(firstOf(e, "endbeat", "endBeat", "end_beat", "endtime"));
                boolean hasEnd = !Double.isNaN(end) && end > b;
                int type = (int) numOf(firstOf(e, "type", "noteType"), hasEnd ? 1 : 0);
                // extra 里 type>=1 且带尾判的是长条；其它（特效、音效）忽略
                if (!hasEnd && type < 2) continue;
                int col = (int) numOf(e.get("x"), 0);
                maxColSeen = Math.max(maxColSeen, col);
                maxBeat = Math.max(maxBeat, end > b ? end : b);
                raw.add(new double[]{col, b, hasEnd ? end : Double.NaN, hasEnd ? 1 : 0});
            }
        }

        int columns = rawCols > 0 ? rawCols : (maxColSeen + 1);
        if (columns <= 0) columns = 4;
        if (columns > 7) columns = Math.min(columns, 10);

        Timing timing = Timing.of(
                bpmPoints.stream().mapToDouble(p -> p[0]).toArray(),
                bpmPoints.stream().mapToDouble(p -> p[1]).toArray(),
                maxBeat + 8);

        Chart.Builder b = Chart.builder();
        b.title = title;
        b.titleOrig = titleOrig;
        b.artist = artist;
        b.artistOrig = artistOrig;
        b.creator = orEmpty(Json.str(meta.get("creator"), null));
        b.difficulty = orEmpty(firstNonBlank(Json.str(meta.get("version"), null),
                Json.str(meta.get("difficulty"), null), null));
        b.level = orEmpty(firstNonBlank(Json.str(meta.get("level"), null),
                Json.str(modeExt.get("level"), null), null));
        b.sourcePath = path == null ? "?" : path.toString();
        b.container = container == null ? "" : container;
        b.columns = columns;
        b.rawColumns = columns;
        b.rawMode = rawMode;
        b.scrollHint = (int) numOf(modeExt.get("speed"), 0);
        b.timing = timing;

        // 音源偏移：Malody 的 SoundCue“等 offset 毫秒再播放”意味着
        // 音频第 0 帧对应谱面 offset 处，所以要整体前移 offset。
        double shift = -cueOffsetMs;
        double minT = Double.MAX_VALUE;
        for (double[] r : raw) minT = Math.min(minT, timing.msAt(r[1]) + shift);
        if (minT < 0) shift -= minT; // 不让音符负时间
        for (double[] r : raw) {
            int col = (int) r[0];
            if (col < 0 || col >= columns) { dropped++; continue; }
            double t = timing.msAt(r[1]) + shift;
            boolean hold = !Double.isNaN(r[2]);
            double end = hold ? timing.msAt(r[2]) + shift : t;
            b.add(col, t, end, hold);
        }

        // ---- 媒体
        String audioReq = firstNonBlank(Json.str(meta.get("audio"), null),
                Json.str(meta.get("songfile"), null),
                Json.str(song.get("file"), null),
                Json.str(song.get("audio"), null),
                soundCue, cueSound, null);
        String coverReq = firstNonBlank(Json.str(meta.get("cover"), null),
                Json.str(meta.get("jacket"), null), null);
        String bgReq = firstNonBlank(Json.str(meta.get("background"), null),
                Json.str(meta.get("bg"), null), null);

        String audioName = findEntry(src, audioReq, AUDIO_EXT, null);
        String coverName = findEntry(src, coverReq != null ? coverReq : bgReq, IMAGE_EXT, "cover");
        String bgName = findEntry(src, bgReq, IMAGE_EXT, "bg");
        if (coverName == null) coverName = bgName;
        if (bgName == null) bgName = coverName;

        byte[] audio = withMedia && audioName != null ? readAny(src, audioName) : null;
        byte[] cover = withMedia && coverName != null ? readAny(src, coverName) : null;
        byte[] bg = withMedia && bgName != null ? readAny(src, bgName) : null;
        if (withMedia && cover != null && bg == cover) bg = cover;

        b.audioEntry = audioName;
        b.coverEntry = coverName;
        b.backgroundEntry = bgName;

        StringBuilder warn = new StringBuilder();
        if (dropped > 0) warn.append("有 ").append(dropped).append(" 个越界音符被忽略; ");
        if (withMedia && audio == null) warn.append("缺少音频文件，将使用静音时钟; ");
        if (columns != 4) warn.append("非 4K 谱面（").append(columns).append("K）; ");
        if (bpmPoints.size() > 64) warn.append("速度点较多(").append(bpmPoints.size()).append(")，已完整解析; ");
        if (warn.length() > 2) warn.setLength(warn.length() - 2);
        b.warning = warn.toString();

        Chart chart = b.build();
        if (chart.noteCount() == 0) {
            Log.warn("谱面没有有效音符: " + chart);
            return false;
        }
        String note = null;
        if (withMedia && audio == null) note = "缺少音频";
        out.add(new ChartEntry(chart, audio, audioName, cover, bg, note));
        return true;
    }

    private static String fileStem(Path path) {
        if (path == null) return "Untitled";
        String n = path.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ------------------------------------------------------------------ 按需读媒体

    /** 内层容器解压缓存（.mcz 里读封面时避免反复解 10MB 的音频）。 */
    private static String cacheKey;
    private static Map<String, byte[]> cacheMap;

    /**
     * 只读出某个媒体条目，用于曲库懒加载封面（不碰音频，省内存）。
     *
     * @param container 内层 .mc 条目名，顶层文件传空串
     */
    public static byte[] readMedia(Path path, String container, String entry) {
        if (entry == null || entry.isEmpty() || path == null) return null;
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".json")) {
                Path dir = path.toAbsolutePath().getParent();
                Path file = dir == null ? Path.of(entry) : dir.resolve(entry);
                return Files.exists(file) ? Files.readAllBytes(file) : null;
            }
            try (ZipFile zf = new ZipFile(path.toFile())) {
                if (container == null || container.isEmpty()) {
                    ZipEntry e = zf.getEntry(entry);
                    if (e == null) {
                        Enumeration<? extends ZipEntry> en = zf.entries();
                        while (en.hasMoreElements()) {
                            ZipEntry c = en.nextElement();
                            if (!c.isDirectory() && c.getName().equalsIgnoreCase(entry)) { e = c; break; }
                        }
                    }
                    return e == null ? null : readAll(zf.getInputStream(e), 128L << 20);
                }
                String key = path.toAbsolutePath() + "|" + container;
                Map<String, byte[]> map;
                if (key.equals(cacheKey) && cacheMap != null) {
                    map = cacheMap;
                } else {
                    ZipEntry ce = zf.getEntry(container);
                    if (ce == null) return null;
                    map = unzip(readAll(zf.getInputStream(ce), 128L << 20), 128L << 20);
                    cacheKey = key;
                    cacheMap = map;
                }
                byte[] v = map.get(entry);
                if (v == null) {
                    for (Map.Entry<String, byte[]> e : map.entrySet()) {
                        if (e.getKey().equalsIgnoreCase(entry)) { v = e.getValue(); break; }
                    }
                }
                return v;
            }
        } catch (Exception e) {
            Log.debug("读取媒体失败 " + entry + ": " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static final String[] AUDIO_EXT = {".ogg", ".mp3", ".wav", ".m4a", ".flac", ".aac", ".opus"};
    private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp", ".bmp"};

    /** 在包里找媒体条目：先按声明名，再按大小写/同名不同目录，最后按扩展名兜底。 */
    private static String findEntry(Src src, String requested, String[] exts, String hint) {
        List<String> names = new ArrayList<>();
        for (Src cur = src; cur != null; cur = cur.parent()) names.addAll(cur.names());
        if (names.isEmpty()) return null;

        if (requested != null) {
            String req = uriDecode(requested).replace('\\', '/');
            while (req.startsWith("./")) req = req.substring(2);
            String exact = null;
            String base = null;
            String baseNoExt = null;
            int slash = req.lastIndexOf('/');
            String reqBase = slash >= 0 ? req.substring(slash + 1) : req;
            for (String name : names) {
                String n = uriDecode(name).replace('\\', '/');
                String nBase = n.substring(n.lastIndexOf('/') + 1);
                if (n.equalsIgnoreCase(req)) { exact = name; break; }
                if (base == null && nBase.equalsIgnoreCase(reqBase)) base = name;
                if (baseNoExt == null && stripExt(nBase).equalsIgnoreCase(stripExt(reqBase))) baseNoExt = name;
            }
            if (exact != null && hasExt(exact, exts)) return exact;
            if (base != null && hasExt(base, exts)) return base;
            if (baseNoExt != null && hasExt(baseNoExt, exts)) return baseNoExt;
        }

        // 兜底：按扩展名 + 提示词挑选（优先浅层目录、优先名字里带 cover/bg 的）
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String name : names) {
            if (!hasExt(name, exts)) continue;
            int score = 0;
            String low = name.toLowerCase(Locale.ROOT);
            if (hint != null) {
                if (low.contains(hint)) score += 20;
                if (hint.equals("cover") && (low.contains("jacket") || low.contains("bg"))) score += 6;
                if (hint.equals("bg") && low.contains("back")) score += 6;
            }
            score -= (int) low.chars().filter(c -> c == '/').count() * 2;
            if (low.contains("preview")) score -= 30;
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
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

    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) return m.get(k);
        }
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static double numOf(Object o, double def) {
        return Json.num(o, def);
    }

    /** beat 数组 -> 绝对拍数（四分音符）。[小节, 分子, 分母] => (小节 + 分子/分母) * 4。 */
    static double beatOf(Object o) {
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
                double bar = Json.num(l.get(0), 0);
                double num = Json.num(l.get(1), 0);
                return bar * 4.0 + num;
            }
            if (l.size() == 1) return Json.num(l.get(0), 0) * 4.0;
        }
        if (o instanceof Number n) return n.doubleValue();
        return Double.NaN;
    }

    /**
     * 读一个 note 对象，返回 {col, startBeat, endBeat(可能 NaN)}。
     *
     * <p>字段兼容：老格式用 {@code x}，Malody V 用 {@code column}；
     * 没有列号的是「音源事件」（type=1 + sound/offset），返回 null 由调用方单独处理。
     */
    private static double[] readNote(Object o) {
        Map<String, Object> m = Json.asObj(o);
        if (m == null) return null;
        double b = beatOf(m.get("beat"));
        if (Double.isNaN(b)) return null;
        Object colObj = firstOf(m, "column", "x");
        if (colObj == null) return null;
        int col = (int) numOf(colObj, -1);
        if (col < 0) return null;

        double endBeat = beatOf(firstOf(m, "endbeat", "endBeat"));
        boolean hasEnd = !Double.isNaN(endBeat) && endBeat > b;
        return new double[]{col, b, hasEnd ? endBeat : Double.NaN};
    }

    /** 音源事件（Malody V 写在 note 数组里：{"type":1,"sound":"x.ogg","offset":478}）。 */
    private static boolean isSoundCue(Map<String, Object> m) {
        if (firstOf(m, "column", "x") != null) return false;
        double type = numOf(m.get("type"), -1);
        return m.get("sound") != null || type == 1;
    }

    private static Map<String, byte[]> siblingMedia(Path dir) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        if (dir == null) return m;
        File[] files = dir.toFile().listFiles();
        if (files == null) return m;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile()) continue;
            String low = f.getName().toLowerCase(Locale.ROOT);
            boolean keep = false;
            for (String e : AUDIO_EXT) keep |= low.endsWith(e);
            for (String e : IMAGE_EXT) keep |= low.endsWith(e);
            if (!keep) continue;
            if (f.length() > 128L << 20) continue;
            try {
                m.put(f.getName(), Files.readAllBytes(f.toPath()));
            } catch (IOException ignored) {
                // 读不了就跳过，不影响其它资源
            }
        }
        return m;
    }

    private static Map<String, byte[]> unzip(byte[] data, long limit) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] b = readAll(zin, limit);
                total += b.length;
                if (total > limit) break;
                out.put(e.getName(), b);
            }
        } catch (IOException ex) {
            Log.debug("内层 ZIP 解析失败: " + ex.getMessage());
        }
        return out;
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
}
