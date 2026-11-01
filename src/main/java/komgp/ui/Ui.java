package komgp.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

import komgp.render.Painter;
import komgp.render.Theme;

/** 轻量 UI 组件（立即模式风格：布局固定，渲染时顺便算命中）。 */
public final class Ui {

    private Ui() {}

    /** 矩形按钮。 */
    public static final class Button {
        public final double x;
        public final double y;
        public final double w;
        public final double h;
        public final String label;
        public final Runnable action;

        public Button(double x, double y, double w, double h, String label, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.action = action;
        }

        public boolean hit(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        public void draw(Graphics2D g, boolean focused, double mx, double my) {
            boolean hover = hit(mx, my);
            Color fill = focused ? Theme.ACCENT : (hover ? Theme.BG3 : Theme.BG2);
            Color border = focused ? Theme.darken(Theme.ACCENT, 0.35f) : Theme.LINE;
            Painter.round(g, x + 4, y + 4, w, h, 10, Theme.alpha(Theme.SHADOW, 190));
            Painter.round(g, x, y, w, h, 10, fill);
            Painter.strokeRound(g, x, y, w, h, 10, border, 2f);
            Painter.textC(g, label, x + w / 2, y + h / 2 + 7,
                    Theme.f(19, focused), focused ? Theme.BG : Theme.TEXT);
        }
    }

    /** 面板背景。 */
    public static void panel(Graphics2D g, double x, double y, double w, double h) {
        Painter.panel(g, x, y, w, h, 14, Theme.BG2);
    }

    // ------------------------------------------------------------------ 贴图缓存
    // 小圆角控件每帧重画（抗锯齿路径）实测每个约 0.1ms，一屏十几个就吃掉 1.5ms。
    // 这里按「文字+颜色+缩放」缓存成设备分辨率贴图，运行时只做 1:1 拷贝。

    private static final java.util.LinkedHashMap<String, java.awt.image.BufferedImage> SPRITES =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, java.awt.image.BufferedImage> e) {
                    return size() > 160;
                }
            };

    private static java.awt.image.BufferedImage sprite(String key, double w, double h,
                                                       java.util.function.Consumer<Graphics2D> painter) {
        java.awt.image.BufferedImage img = SPRITES.get(key);
        if (img != null) return img;
        int pw = Math.max(1, (int) Math.round(w * Painter.scale));
        int ph = Math.max(1, (int) Math.round(h * Painter.scale));
        img = new java.awt.image.BufferedImage(pw, ph,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(Painter.scale, Painter.scale);
        painter.accept(g);
        g.dispose();
        SPRITES.put(key, img);
        return img;
    }

    private static String scaleKey() {
        return Integer.toString((int) Math.round(Painter.scale * 1000));
    }

    /** 小标签（难度、BPM、模式）。 */
    public static void pill(Graphics2D g, double x, double y, String text, Color color, boolean filled) {
        Font f = Theme.f(13, true);
        double w = Painter.width(g, text, f) + 18;
        String key = "pill|" + text + "|" + color.getRGB() + "|" + filled + "|" + scaleKey();
        java.awt.image.BufferedImage img = sprite(key, w, 22, sg -> {
            if (filled) {
                Painter.round(sg, 0, 0, w, 22, 6, color);
                Painter.strokeRound(sg, 0, 0, w, 22, 6, Theme.darken(color, 0.35f), 2f);
                Painter.text(sg, text, 9, 16, f, Theme.BG);
            } else {
                Painter.round(sg, 0, 0, w, 22, 6, Theme.alpha(color, 40));
                Painter.strokeRound(sg, 0, 0, w, 22, 6, color, 1.6f);
                Painter.text(sg, text, 9, 16, f, color);
            }
        });
        Painter.blitExact(g, img, x, y);
        lastPillWidth = w;
    }

    private static double lastPillWidth = 60;

    public static double pillWidth() { return lastPillWidth; }

    public static double pillWidthOf(Graphics2D g, String text) {
        return Painter.width(g, text, Theme.f(13, true)) + 18;
    }

    /** 难度徽章：Level 数字 + 颜色。 */
    public static void levelBadge(Graphics2D g, double cx, double cy, String text, int level,
                                  boolean big) {
        Color c = Theme.levelColor(level);
        double r = big ? 30 : 22;
        double size = r * 3;
        String key = "lvl|" + text + "|" + level + "|" + big + "|" + scaleKey();
        java.awt.image.BufferedImage img = sprite(key, size, size, sg -> {
            double ox = size / 2;
            double oy = size / 2;
            Painter.round(sg, ox - r + 3, oy - r + 3, r * 2, r * 2, r * 0.45,
                    Theme.alpha(Theme.SHADOW, 180));
            Painter.round(sg, ox - r, oy - r, r * 2, r * 2, r * 0.45,
                    Theme.mix(c, Theme.BG, 0.45f));
            Painter.strokeRound(sg, ox - r, oy - r, r * 2, r * 2, r * 0.45,
                    Theme.darken(c, 0.2f), 2f);
            Painter.textC(sg, text, ox, oy + (big ? 9 : 6), Theme.f(big ? 24 : 17, true), c);
        });
        Painter.blitExact(g, img, cx - size / 2, cy - size / 2);
    }

    /** 键帽。 */
    public static void keyCap(Graphics2D g, double cx, double cy, String label, Color color,
                              boolean pressed, double w) {
        double h = w * 0.86;
        String key = "key|" + label + "|" + color.getRGB() + "|" + pressed + "|" + Math.round(w)
                + "|" + scaleKey();
        java.awt.image.BufferedImage img = sprite(key, w, h, sg -> {
            Painter.round(sg, 2, 3, w - 2, h - 2, 6, Theme.alpha(Theme.SHADOW, 170));
            Painter.round(sg, 0, 0, w - 2, h - 2, 6, pressed ? color : Theme.BG3);
            Painter.strokeRound(sg, 0, 0, w - 2, h - 2, 6,
                    pressed ? Theme.darken(color, 0.3f) : Theme.LINE, 2f);
            Painter.textC(sg, label, (w - 2) / 2, (h - 2) / 2 + 5, Theme.f(13, true),
                    pressed ? Theme.BG : Theme.TEXT);
        });
        Painter.blitExact(g, img, cx - w / 2, cy - h / 2 + (pressed ? 2 : 0));
    }

    public static String keyName(int vk) {
        String s = java.awt.event.KeyEvent.getKeyText(vk);
        return switch (vk) {
            case java.awt.event.KeyEvent.VK_SPACE -> "空格";
            case java.awt.event.KeyEvent.VK_UP -> "↑";
            case java.awt.event.KeyEvent.VK_DOWN -> "↓";
            case java.awt.event.KeyEvent.VK_LEFT -> "←";
            case java.awt.event.KeyEvent.VK_RIGHT -> "→";
            case java.awt.event.KeyEvent.VK_ENTER -> "Enter";
            case java.awt.event.KeyEvent.VK_ESCAPE -> "Esc";
            case java.awt.event.KeyEvent.VK_SHIFT -> "Shift";
            case java.awt.event.KeyEvent.VK_CONTROL -> "Ctrl";
            case java.awt.event.KeyEvent.VK_ALT -> "Alt";
            case java.awt.event.KeyEvent.VK_TAB -> "Tab";
            case java.awt.event.KeyEvent.VK_BACK_QUOTE -> "`";
            default -> {
                if (s.length() > 6) yield s.substring(0, 6);
                yield s;
            }
        };
    }
}
