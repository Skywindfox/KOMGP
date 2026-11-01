package komgp.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 背景层 —— 2D 扁平版本。
 *
 * <p>不再对曲绘做高斯模糊：取曲绘的平均色，铺一个纯色底，再压几块大几何形
 * （圆 / 斜条 / 方框），视觉上是「海报风」，也更符合 2D 画风。
 * 结果按 (曲绘, 尺寸) 缓存，播放中每帧只有一次整图 drawImage。
 */
public final class Backdrop {

    private static final Map<String, BufferedImage> CACHE = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > 4;
        }
    };

    private Backdrop() {}

    public static boolean matches(BufferedImage img) {
        return img != null && img.getWidth() == Painter.devW && img.getHeight() == Painter.devH;
    }

    public static BufferedImage ofDevice(BufferedImage src, double dim, double blur) {
        return of(src, Painter.devW, Painter.devH, dim, blur);
    }

    /**
     * @param dim  0..1，越大越暗（保留参数以兼容调用点）
     * @param blur 已废弃，仅参与缓存键
     */
    public static BufferedImage of(BufferedImage src, int w, int h, double dim, double blur) {
        if (src == null) src = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        String key = System.identityHashCode(src) + ":" + w + "x" + h + ":" + Math.round(dim * 100);
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;

        Color avg = averageColor(src);
        // 底色：曲绘主色压暗后的纯色
        Color base = Theme.darken(Theme.mix(avg, Theme.BG, 0.55f), 0.35f);
        int dark = (int) Math.min(235, 90 + dim * 170);
        base = Theme.mix(base, Theme.BG, dark / 255f * 0.75f);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(base);
        g.fillRect(0, 0, w, h);

        // 大几何装饰（纯色块，2D 海报感）
        Color deco = Theme.mix(avg, Color.WHITE, 0.35f);
        g.setColor(Theme.alpha(deco, 26));
        int r = Math.max(w, h) / 3;
        g.fillOval(w - r * 3 / 2, -r / 2, r, r);
        g.fillOval(-r / 2, h - r * 2 / 3, r * 2 / 3, r * 2 / 3);

        g.setColor(Theme.alpha(deco, 16));
        for (int i = -h; i < w; i += 96) {
            g.fillPolygon(new int[]{i, i + 34, i + 34 + h, i + h},
                    new int[]{0, 0, h, h}, 4);
        }

        // 底部压深，保证文字对比度
        g.setColor(Theme.alpha(Theme.BG, 120));
        g.fillRect(0, (int) (h * 0.62), w, (int) (h * 0.38));
        g.dispose();

        CACHE.put(key, out);
        return out;
    }

    /** 粗略平均色：隔行隔列采样，避免逐像素。 */
    public static Color averageColor(BufferedImage img) {
        if (img == null) return Theme.BG2;
        long r = 0;
        long gg = 0;
        long b = 0;
        long n = 0;
        int step = Math.max(1, Math.max(img.getWidth(), img.getHeight()) / 48);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int rgb = img.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                gg += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                n++;
            }
        }
        if (n == 0) return Theme.BG2;
        return new Color((int) (r / n), (int) (gg / n), (int) (b / n));
    }
}
