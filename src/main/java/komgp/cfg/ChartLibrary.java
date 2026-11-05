package komgp.cfg;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import komgp.core.Chart;
import komgp.format.ChartEntry;
import komgp.format.ChartIO;
import komgp.format.ChartPackage;
import komgp.util.Log;

/**
 * 曲库：扫描 charts/ 目录，把每个谱面文件解析成可展示/可游玩的条目。
 *
 * <p>扫描阶段只读谱面 JSON（不读音频），封面按需懒加载并做 LRU 缓存，
 * 保证几百张谱面的曲库也能秒开。
 */
public final class ChartLibrary {

    /** 一张谱面（一张难度）。 */
    public static final class Item {
        public final Path path;
        public final Chart chart;
        public final int indexInPackage;
        public final String id;

        Item(Path path, Chart chart, int indexInPackage) {
            this.path = path;
            this.chart = chart;
            this.indexInPackage = indexInPackage;
            this.id = chart.fingerprint;
        }
    }

    /** 一首歌（若干难度）。 */
    public static final class Song {
        public final String key;
        public final String title;
        public final String artist;
        public final List<Item> items = new ArrayList<>();

        Song(String key, String title, String artist) {
            this.key = key;
            this.title = title;
            this.artist = artist;
        }

        public Item first() { return items.get(0); }

        public Item hardest() {
            Item best = items.get(0);
            for (Item i : items) if (i.chart.levelNumber() > best.chart.levelNumber()) best = i;
            return best;
        }
    }

    public final Path chartsDir;
    public final List<Song> songs = new ArrayList<>();
    public final List<Item> items = new ArrayList<>();
    public int skipped;
    public String lastError;
    public long scanMillis;

    private final Map<String, BufferedImage> imageCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > 14;
        }
    };

    public ChartLibrary(Path chartsDir) {
        this.chartsDir = chartsDir;
    }

    // ------------------------------------------------------------------ 扫描

    public void scan() {
        long t0 = System.nanoTime();
        songs.clear();
        items.clear();
        skipped = 0;
        lastError = null;
        try {
            Files.createDirectories(chartsDir);
            List<Path> files;
            try (Stream<Path> s = Files.walk(chartsDir, 4)) {
                files = s.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".kmt") || n.endsWith(".mc")
                                    || n.endsWith(".mcz") || n.endsWith(".json");
                        })
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            Map<String, Song> groups = new LinkedHashMap<>();
            for (Path p : files) {
                ChartPackage pkg = ChartIO.load(p, false);
                if (!pkg.ok()) {
                    skipped++;
                    lastError = p.getFileName() + ": " + pkg.error;
                    Log.warn("跳过 " + lastError);
                    continue;
                }
                int idx = 0;
                for (ChartEntry lc : pkg.charts) {
                    Item it = new Item(p, lc.chart, idx++);
                    items.add(it);
                    String key = (lc.chart.displayTitle() + "|" + lc.chart.displayArtist())
                            .toLowerCase(Locale.ROOT).trim();
                    Song g = groups.get(key);
                    if (g == null) {
                        g = new Song(key, lc.chart.displayTitle(), lc.chart.displayArtist());
                        groups.put(key, g);
                        songs.add(g);
                    }
                    g.items.add(it);
                }
            }
            for (Song s : songs) {
                s.items.sort(Comparator.comparingInt(i -> i.chart.levelNumber()));
            }
            songs.sort(Comparator.comparing(s -> s.title.toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            lastError = e.getMessage();
            Log.error("扫描曲库失败", e);
        }
        scanMillis = (System.nanoTime() - t0) / 1_000_000;
        Log.info("曲库: " + songs.size() + " 首歌 / " + items.size() + " 张谱面, 跳过 "
                + skipped + ", 用时 " + scanMillis + "ms");
    }

    /** 按歌名/难度模糊查找，供命令行导入与工具使用。 */
    public Item findByName(String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (Song s : songs) {
            if (s.title.toLowerCase(Locale.ROOT).contains(n)) return s.hardest();
        }
        for (Item i : items) {
            if (i.chart.difficulty.toLowerCase(Locale.ROOT).contains(n)) return i;
        }
        return items.isEmpty() ? null : items.get(0);
    }

    public Item findById(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }

    public Song songOf(Item item) {
        for (Song s : songs) if (s.items.contains(item)) return s;
        return null;
    }

    // ------------------------------------------------------------------ 媒体

    /** 封面（方图，最长边 512）。没有封面时生成一张确定性的占位图。 */
    public BufferedImage cover(Item it) {
        String key = "cover:" + it.id;
        BufferedImage img = imageCache.get(key);
        if (img != null) return img;
        byte[] raw = ChartIO.readMedia(it.path, it.chart.container, it.chart.coverEntry);
        img = decodeSquare(raw);
        if (img == null) img = placeholder(it.chart);
        imageCache.put(key, img);
        return img;
    }

    /** 游戏背景图：优先 background，其次封面。 */
    public BufferedImage backdrop(Item it) {
        String key = "bg:" + it.id;
        BufferedImage img = imageCache.get(key);
        if (img != null) return img;
        byte[] raw = null;
        if (it.chart.backgroundEntry != null && !it.chart.backgroundEntry.equals(it.chart.coverEntry)) {
            raw = ChartIO.readMedia(it.path, it.chart.container, it.chart.backgroundEntry);
        }
        img = decode(raw);
        if (img == null) img = cover(it);
        imageCache.put(key, img);
        return img;
    }

    private static BufferedImage decode(byte[] raw) {
        if (raw == null || raw.length < 8) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static BufferedImage decodeSquare(byte[] raw) {
        BufferedImage src = decode(raw);
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        int out = Math.min(512, side);
        BufferedImage dst = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, out, out, x, y, x + side, y + side, null);
        g.dispose();
        return dst;
    }

    /** 没有曲绘时按标题哈希生成一张赛博风格占位图，保证 UI 不出现空洞。 */
    private static BufferedImage placeholder(Chart chart) {
        int size = 512;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int h = chart.displayTitle().hashCode();
        float hue = ((h >>> 8) & 0xFF) / 255f;
        Color a = Color.getHSBColor(hue, 0.55f, 0.55f);
        Color b = Color.getHSBColor((hue + 0.12f) % 1f, 0.75f, 0.18f);
        g.setPaint(new GradientPaint(0, 0, a, size, size, b));
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(255, 255, 255, 26));
        for (int i = -size; i < size * 2; i += 28) g.drawLine(i, 0, i + size, size);
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(0, size * 2 / 3, size, size / 3);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 120));
        String t = chart.displayTitle().isEmpty() ? "?" : chart.displayTitle().substring(0, 1);
        g.drawString(t, 48, size - 120);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ 导入

    /** 把外部谱面复制进曲库目录，返回是否成功。 */
    public boolean importFile(Path file) {
        return importTo(file, chartsDir) != null;
    }

    /**
     * 把文件复制进指定目录（默认不动已在该目录里的文件），返回落地路径。
     * 同名时自动加 -1 / -2 后缀，不会覆盖用户文件。
     */
    public static Path importTo(Path file, Path dir) {
        try {
            Files.createDirectories(dir);
            Path abs = file.toAbsolutePath();
            Path targetDir = dir.toAbsolutePath();
            if (abs.getParent() != null && abs.getParent().equals(targetDir)) {
                return abs;
            }
            String name = file.getFileName().toString();
            Path dst = targetDir.resolve(name);
            int n = 1;
            while (Files.exists(dst)) {
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                dst = targetDir.resolve(stem + "-" + (n++) + ext);
            }
            Files.copy(file, dst, StandardCopyOption.COPY_ATTRIBUTES);
            return dst;
        } catch (IOException e) {
            Log.warn("导入失败 " + file + ": " + e.getMessage());
            return null;
        }
    }
}
