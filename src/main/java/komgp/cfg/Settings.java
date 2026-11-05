package komgp.cfg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import komgp.format.Json;
import komgp.util.Log;

/**
 * 全局设置 + 每张谱面的偏移/最佳成绩，持久化到 config.json 与谱面放在一起。
 *
 * <p>偏移量的语义（很容易搞混，这里写死）：
 * <ul>
 *   <li>{@code audioOffset}：全局音频偏移，正值 = 判定时间整体往后推（打起来更「早」）；</li>
 *   <li>{@code visualOffset}：只影响画面，正值 = 音符更早出现在判定线上方；</li>
 *   <li>{@code chartOffsets[fingerprint]}：单曲偏移，叠加在全局之上。</li>
 * </ul>
 */
public final class Settings {

    public static final String FILE = "config.json";

    // ---- 音频 / 判定
    public double audioOffsetMs = 0;
    public double visualOffsetMs = 0;
    public double volume = 0.6;
    public double sfxVolume = 0.45;
    public double judgeScale = 1.0;
    public boolean practiceMode = false;
    public boolean autoplay = false;

    // ---- 画面
    public double scrollSpeed = 2.6;
    public double playRate = 1.0;
    public double bgDim = 0.55;
    public boolean noteEffects = true;
    public boolean showFps = false;
    /** 垂直同步：等一次屏幕刷新再进入下一帧，帧间隔才均匀。 */
    public boolean vsync = true;
    public boolean showLaneGlow = true;
    /** 0 = 跟随显示器刷新率（推荐：高于刷新率只会造成不均匀呈现）。 */
    public int targetFps = 0;
    public boolean fullscreen = false;
    public int windowWidth = 1280;
    public int windowHeight = 720;

    // ---- 键位：列数 -> KeyEvent VK 码数组
    public final Map<Integer, int[]> keyLayouts = new LinkedHashMap<>();
    /** 键位设置页当前编辑的列数。 */
    public int keyEditColumns = 4;

    // ---- 每张谱面的数据
    public final Map<String, Double> chartOffsets = new LinkedHashMap<>();
    public final Map<String, Long> bestScores = new LinkedHashMap<>();
    public String lastChart = "";

    public Settings() {
        defaults();
    }

    private void defaults() {
        keyLayouts.put(1, new int[]{java.awt.event.KeyEvent.VK_SPACE});
        keyLayouts.put(2, new int[]{java.awt.event.KeyEvent.VK_D, java.awt.event.KeyEvent.VK_K});
        keyLayouts.put(3, new int[]{java.awt.event.KeyEvent.VK_D, java.awt.event.KeyEvent.VK_SPACE,
                java.awt.event.KeyEvent.VK_K});
        keyLayouts.put(4, new int[]{java.awt.event.KeyEvent.VK_D, java.awt.event.KeyEvent.VK_F,
                java.awt.event.KeyEvent.VK_J, java.awt.event.KeyEvent.VK_K});
        keyLayouts.put(5, new int[]{java.awt.event.KeyEvent.VK_D, java.awt.event.KeyEvent.VK_F,
                java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.VK_J, java.awt.event.KeyEvent.VK_K});
        keyLayouts.put(6, new int[]{java.awt.event.KeyEvent.VK_S, java.awt.event.KeyEvent.VK_D,
                java.awt.event.KeyEvent.VK_F, java.awt.event.KeyEvent.VK_J, java.awt.event.KeyEvent.VK_K,
                java.awt.event.KeyEvent.VK_L});
        keyLayouts.put(7, new int[]{java.awt.event.KeyEvent.VK_S, java.awt.event.KeyEvent.VK_D,
                java.awt.event.KeyEvent.VK_F, java.awt.event.KeyEvent.VK_SPACE, java.awt.event.KeyEvent.VK_J,
                java.awt.event.KeyEvent.VK_K, java.awt.event.KeyEvent.VK_L});
    }

    public int[] keys(int columns) {
        int[] k = keyLayouts.get(columns);
        if (k != null && k.length == columns) return k;
        int[] fallback = keyLayouts.get(4);
        int[] out = new int[columns];
        for (int i = 0; i < columns; i++) out[i] = fallback[Math.min(i, fallback.length - 1)];
        return out;
    }

    public void setKey(int columns, int index, int vk) {
        int[] cur = keyLayouts.computeIfAbsent(columns, c -> new int[c]);
        if (index >= 0 && index < cur.length) cur[index] = vk;
    }

    public double offsetFor(String fingerprint) {
        Double d = chartOffsets.get(fingerprint);
        return d == null ? 0 : d;
    }

    public void setOffsetFor(String fingerprint, double ms) {
        if (Math.abs(ms) < 0.05) chartOffsets.remove(fingerprint);
        else chartOffsets.put(fingerprint, Math.round(ms * 100) / 100.0);
    }

    public long bestFor(String fingerprint) {
        Long v = bestScores.get(fingerprint);
        return v == null ? 0 : v;
    }

    public void recordBest(String fingerprint, long score) {
        if (score > bestFor(fingerprint)) bestScores.put(fingerprint, score);
    }

    // ------------------------------------------------------------------ 存取

    private Path file;

    public void bind(Path file) { this.file = file; }

    public static Settings load(Path file) {
        Settings s = new Settings();
        s.file = file;
        if (!Files.exists(file)) {
            s.save();
            return s;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> m = Json.asObj(Json.parse(text));
            if (m == null) return s;
            s.audioOffsetMs = clamp(Json.num(m.get("audioOffset"), s.audioOffsetMs), -500, 500);
            s.visualOffsetMs = clamp(Json.num(m.get("visualOffset"), s.visualOffsetMs), -200, 200);
            s.volume = clamp(Json.num(m.get("volume"), s.volume), 0, 1.5);
            s.sfxVolume = clamp(Json.num(m.get("sfxVolume"), s.sfxVolume), 0, 1);
            s.judgeScale = clamp(Json.num(m.get("judgeScale"), s.judgeScale), 0.5, 3);
            s.practiceMode = Json.bool(m.get("practice"), s.practiceMode);
            s.autoplay = Json.bool(m.get("autoplay"), s.autoplay);
            s.scrollSpeed = clamp(Json.num(m.get("scrollSpeed"), s.scrollSpeed), 0.5, 10);
            s.playRate = clamp(Json.num(m.get("playRate"), s.playRate), 0.5, 1.5);
            s.bgDim = clamp(Json.num(m.get("bgDim"), s.bgDim), 0, 0.95);
            s.noteEffects = Json.bool(m.get("noteEffects"), s.noteEffects);
            s.showFps = Json.bool(m.get("showFps"), s.showFps);
            s.vsync = Json.bool(m.get("vsync"), s.vsync);
            s.showLaneGlow = Json.bool(m.get("laneGlow"), s.showLaneGlow);
            s.targetFps = (int) clamp(Json.num(m.get("targetFps"), s.targetFps), 0, 480);
            s.fullscreen = Json.bool(m.get("fullscreen"), s.fullscreen);
            s.windowWidth = (int) clamp(Json.num(m.get("windowWidth"), s.windowWidth), 800, 3840);
            s.windowHeight = (int) clamp(Json.num(m.get("windowHeight"), s.windowHeight), 450, 2160);
            s.keyEditColumns = (int) clamp(Json.num(m.get("keyEditColumns"), s.keyEditColumns), 1, 7);
            s.lastChart = Json.str(m.get("lastChart"), "");

            Map<String, Object> keys = Json.asObj(m.get("keyLayouts"));
            if (keys != null) {
                for (Map.Entry<String, Object> e : keys.entrySet()) {
                    int cols;
                    try {
                        cols = Integer.parseInt(e.getKey());
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    List<Object> arr = Json.asArr(e.getValue());
                    if (arr == null || arr.size() != cols) continue;
                    int[] vk = new int[cols];
                    for (int i = 0; i < cols; i++) vk[i] = (int) Json.num(arr.get(i), 0);
                    s.keyLayouts.put(cols, vk);
                }
            }
            Map<String, Object> offs = Json.asObj(m.get("chartOffsets"));
            if (offs != null) {
                for (Map.Entry<String, Object> e : offs.entrySet()) {
                    s.chartOffsets.put(e.getKey(), Json.num(e.getValue(), 0));
                }
            }
            Map<String, Object> best = Json.asObj(m.get("best"));
            if (best != null) {
                for (Map.Entry<String, Object> e : best.entrySet()) {
                    s.bestScores.put(e.getKey(), (long) Json.num(e.getValue(), 0));
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("读取配置失败，使用默认值: " + e.getMessage());
        }
        return s;
    }

    public void save() {
        if (file == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("audioOffset", audioOffsetMs);
        m.put("visualOffset", visualOffsetMs);
        m.put("volume", volume);
        m.put("sfxVolume", sfxVolume);
        m.put("judgeScale", judgeScale);
        m.put("practice", practiceMode);
        m.put("autoplay", autoplay);
        m.put("scrollSpeed", scrollSpeed);
        m.put("playRate", playRate);
        m.put("bgDim", bgDim);
        m.put("noteEffects", noteEffects);
        m.put("showFps", showFps);
        m.put("vsync", vsync);
        m.put("laneGlow", showLaneGlow);
        m.put("targetFps", targetFps);
        m.put("fullscreen", fullscreen);
        m.put("windowWidth", windowWidth);
        m.put("windowHeight", windowHeight);
        m.put("keyEditColumns", keyEditColumns);
        m.put("lastChart", lastChart);

        Map<String, Object> keys = new LinkedHashMap<>();
        for (Map.Entry<Integer, int[]> e : keyLayouts.entrySet()) {
            List<Object> arr = new java.util.ArrayList<>();
            for (int v : e.getValue()) arr.add(v);
            keys.put(String.valueOf(e.getKey()), arr);
        }
        m.put("keyLayouts", keys);

        Map<String, Object> offs = new LinkedHashMap<>(chartOffsets);
        if (!offs.isEmpty()) m.put("chartOffsets", offs);
        Map<String, Object> best = new LinkedHashMap<>(bestScores);
        if (!best.isEmpty()) m.put("best", best);

        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, Json.write(m, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("保存配置失败: " + e.getMessage());
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
