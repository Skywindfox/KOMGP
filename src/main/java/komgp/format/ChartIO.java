package komgp.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import komgp.util.Log;

/**
 * 谱面加载入口：**按文件内容识别格式**，而不是只看扩展名。
 *
 * <p>识别优先级：
 * <ol>
 *   <li>首行是 {@code KOMGP CHART} → 明文谱面（{@link TextChart}）</li>
 *   <li>{@code PK\x03\x04} → Malody 的 .mc / .mcz（本质是 ZIP）</li>
 *   <li>首个非空白字符是 {@code {} 且含 note/time → 裸 chart.json</li>
 * </ol>
 *
 * <p>这样把 .kmt 改名成 .txt、把 .mc 改名成 .zip 都能正常打开，
 * 也方便用户直接把谱面拖进游戏。
 */
public final class ChartIO {

    public enum Kind { TEXT, ZIP, JSON, UNKNOWN }

    private ChartIO() {}

    public static Kind detect(Path path) {
        byte[] head = head(path, 1024);
        Kind k = detect(head, path.getFileName().toString());
        if (k != Kind.UNKNOWN) return k;
        // 内容识别不出来时，退回扩展名
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(TextChart.EXT)) return Kind.TEXT;
        if (n.endsWith(".mc") || n.endsWith(".mcz")) return Kind.ZIP;
        if (n.endsWith(".json")) return Kind.JSON;
        return Kind.UNKNOWN;
    }

    public static Kind detect(byte[] head, String name) {
        if (head == null || head.length == 0) return Kind.UNKNOWN;
        if (TextChart.looksLikeText(head)) return Kind.TEXT;
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K'
                && (head[2] == 3 || head[2] == 5) && (head[3] == 4 || head[3] == 6)) {
            return Kind.ZIP;
        }
        String s = new String(head, 0, Math.min(head.length, 4096), StandardCharsets.UTF_8).stripLeading();
        if (s.startsWith("{")) {
            if (s.contains("\"note\"") || s.contains("\"time\"") || s.contains("\"meta\"")) {
                return Kind.JSON;
            }
        }
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(TextChart.EXT)) return Kind.TEXT;
        }
        return Kind.UNKNOWN;
    }

    public static String kindName(Kind k) {
        return switch (k) {
            case TEXT -> "明文谱面";
            case ZIP -> "Malody 包";
            case JSON -> "Malody JSON";
            default -> "未知格式";
        };
    }

    private static byte[] head(Path path, int max) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[max];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            return java.util.Arrays.copyOf(buf, n);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    // ------------------------------------------------------------------ 加载

    public static ChartPackage load(Path path, boolean withMedia) {
        Kind k = detect(path);
        Log.debug("识别 " + path.getFileName() + " -> " + kindName(k));
        return switch (k) {
            case TEXT -> TextChart.load(path, withMedia);
            case ZIP, JSON -> MalodyLoader.load(path, withMedia);
            case UNKNOWN -> ChartPackage.failed(path, "unknown",
                    "无法识别的谱面格式（既不是明文谱面也不是 Malody 包）");
        };
    }

    /**
     * 读取单个媒体条目。
     *
     * <p>明文 / 裸 JSON 谱面的媒体是同目录文件；Malody 包里的媒体是 zip 条目。
     */
    public static byte[] readMedia(Path path, String container, String entry) {
        if (entry == null || entry.isBlank() || path == null) return null;
        if (container == null || container.isEmpty()) {
            try {
                Path dir = path.toAbsolutePath().getParent();
                Path f = dir == null ? Path.of(entry) : dir.resolve(entry);
                if (Files.isRegularFile(f)) return Files.readAllBytes(f);
                if (dir != null) {
                    try (var s = Files.list(dir)) {
                        var hit = s.filter(x -> x.getFileName().toString().equalsIgnoreCase(entry))
                                .findFirst();
                        if (hit.isPresent()) return Files.readAllBytes(hit.get());
                    }
                }
            } catch (IOException ignored) {
                // 落到 zip 分支
            }
        }
        return MalodyLoader.readMedia(path, container, entry);
    }

    public static boolean isChartFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(TextChart.EXT) || n.endsWith(".mc") || n.endsWith(".mcz")
                || n.endsWith(".json")) {
            return true;
        }
        Kind k = detect(p);
        return k != Kind.UNKNOWN;
    }
}
