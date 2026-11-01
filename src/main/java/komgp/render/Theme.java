package komgp.render;

import java.awt.Color;
import java.awt.Font;

import komgp.core.Judge;
import komgp.util.Fonts;

/**
 * 配色与排版常量 —— 2D 扁平风格。
 *
 * <p>设计约束：
 * <ul>
 *   <li>只用纯色，不用渐变 / 模糊 / 发光；</li>
 *   <li>面板 = 实色 + 描边 + 右下硬阴影（"贴纸"感）；</li>
 *   <li>主色狐狸橙，辅助薄荷绿，底色深靛。</li>
 * </ul>
 */
public final class Theme {

    public static final int W = 1280;
    public static final int H = 720;

    // ---- 底色
    public static final Color BG = new Color(0x19, 0x16, 0x27);
    public static final Color BG2 = new Color(0x22, 0x1D, 0x33);
    public static final Color BG3 = new Color(0x2C, 0x25, 0x42);
    public static final Color LINE = new Color(0x4A, 0x3F, 0x6B);
    public static final Color SHADOW = new Color(0x10, 0x0D, 0x1B);

    // ---- 前景
    public static final Color TEXT = new Color(0xF2, 0xEC, 0xFF);
    public static final Color MUTED = new Color(0xA8, 0x9F, 0xC7);
    public static final Color DIM = new Color(0x6E, 0x66, 0x8C);

    // ---- 强调
    public static final Color ACCENT = new Color(0xFF, 0x9E, 0x64);   // 狐狸橙
    public static final Color ACCENT2 = new Color(0x7F, 0xD4, 0xC1);  // 薄荷
    public static final Color OK = new Color(0x7F, 0xD4, 0xC1);
    public static final Color WARN = new Color(0xFF, 0xD1, 0x66);
    public static final Color DANGER = new Color(0xFF, 0x6B, 0x81);
    public static final Color GOLD = new Color(0xFF, 0xD1, 0x66);
    public static final Color LILAC = new Color(0xB3, 0x9D, 0xDB);

    /** 每条轨道的强调色（扁平色板）。 */
    public static final Color[] LANE = {
            new Color(0x7F, 0xD4, 0xC1),
            new Color(0xB3, 0x9D, 0xDB),
            new Color(0xFF, 0xD1, 0x66),
            new Color(0xFF, 0x9E, 0x64),
            new Color(0x8F, 0xC9, 0xFF),
            new Color(0xFF, 0x8F, 0xB1),
            new Color(0xC8, 0xE8, 0x8F),
    };

    // ---- 几何
    public static final double LANE_W = 124;
    public static final double NOTE_H = 30;
    public static final double NOTE_INSET = 8;
    public static final double JUDGE_Y = 612;

    // ---- 字体（缓存：每帧几十次取字体，避免反复 new Font）
    private static final java.util.HashMap<Integer, Font> FONT_CACHE = new java.util.HashMap<>();

    public static Font f(float size, boolean bold) {
        int key = (Math.round(size * 4) << 1) | (bold ? 1 : 0);
        Font cached = FONT_CACHE.get(key);
        if (cached != null) return cached;
        Font font = bold ? Fonts.bold(size) : Fonts.plain(size);
        FONT_CACHE.put(key, font);
        return font;
    }

    public static Font title() { return f(64, true); }

    public static Font h1() { return f(30, true); }

    public static Font h2() { return f(22, true); }

    public static Font body() { return f(17, false); }

    public static Font small() { return f(14, false); }

    public static Font tiny() { return f(12, false); }

    public static Font num(float size) { return f(size, true); }

    // ---- 颜色辅助

    public static Color laneColor(int col) {
        return LANE[Math.floorMod(col, LANE.length)];
    }

    public static Color judgeColor(Judge j) {
        return switch (j) {
            case PERFECT_PLUS -> GOLD;
            case PERFECT -> ACCENT2;
            case EARLY -> LILAC;
            case LATE -> ACCENT;
            case BAD -> new Color(0xD8, 0x8F, 0xD0);
            case MISS -> DANGER;
        };
    }

    public static Color levelColor(int level) {
        if (level <= 3) return new Color(0x8F, 0xC9, 0xFF);
        if (level <= 6) return ACCENT2;
        if (level <= 9) return WARN;
        if (level <= 12) return ACCENT;
        if (level <= 14) return DANGER;
        return LILAC;
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    public static Color alpha(Color c, float a) {
        return alpha(c, Math.round(a * 255));
    }

    public static Color mix(Color a, Color b, float t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    public static Color brighten(Color c, float amt) {
        return mix(c, Color.WHITE, amt);
    }

    public static Color darken(Color c, float amt) {
        return mix(c, SHADOW, amt);
    }

    private Theme() {}
}
