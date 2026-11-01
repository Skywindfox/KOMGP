package komgp.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/** Java2D 绘图小工具：统一渲染质量、圆角、渐变、文字对齐、发光。 */
public final class Painter {

    /** 当前设备的缩放（逻辑像素 -> 物理像素）与画布偏移，由渲染循环每帧写入。 */
    public static double scale = 1.0;
    public static double offX;
    public static double offY;
    public static int devW = Theme.W;
    public static int devH = Theme.H;

    public static void setDevice(int w, int h, double s, double ox, double oy) {
        devW = w;
        devH = h;
        scale = s;
        offX = ox;
        offY = oy;
    }

    /**
     * 把一张「已按设备分辨率预渲染」的图 1:1 贴到逻辑坐标 (lx, ly)。
     *
     * <p>这是本项目最关键的性能技巧：Java2D 只要插值不是 NEAREST，即使缩放比是 1.0
     * 也会走逐像素重采样（实测 1280x720 一次要约 11ms，而 1:1 NEAREST 只要 0.8ms）。
     * 所以静态图层一律预渲染到设备分辨率，绘制时临时复位变换 + NEAREST，直接拷像素。
     */
    public static void blitExact(Graphics2D g, BufferedImage img, double lx, double ly) {
        if (img == null) return;
        java.awt.geom.AffineTransform tf = g.getTransform();
        Object interp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        java.awt.Shape clip = g.getClip();
        g.setClip(null);
        g.setTransform(new java.awt.geom.AffineTransform());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, (int) Math.round(offX + lx * scale), (int) Math.round(offY + ly * scale), null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                interp == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : interp);
        g.setTransform(tf);
        if (clip != null) g.setClip(clip);
    }

    /** 判断一张图是否正好是某个逻辑尺寸的设备分辨率版本。 */
    public static boolean isExact(BufferedImage img, double lw, double lh) {
        return img != null
                && Math.abs(img.getWidth() - lw * scale) < 1.5
                && Math.abs(img.getHeight() - lh * scale) < 1.5;
    }

    /** 按逻辑尺寸贴图：尺寸匹配就走 1:1 快速路径，否则退回普通缩放。 */
    public static void drawImage(Graphics2D g, BufferedImage img, double lx, double ly,
                                 double lw, double lh) {
        if (img == null) return;
        if (isExact(img, lw, lh)) {
            blitExact(g, img, lx, ly);
        } else {
            g.drawImage(img, (int) Math.round(lx), (int) Math.round(ly),
                    (int) Math.round(lw), (int) Math.round(lh), null);
        }
    }

    private Painter() {}

    public static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
    }

    public static void fill(Graphics2D g, double x, double y, double w, double h, Color c) {
        g.setColor(c);
        g.fillRect((int) Math.round(x), (int) Math.round(y),
                (int) Math.round(w), (int) Math.round(h));
    }

    public static void round(Graphics2D g, double x, double y, double w, double h, double r, Color c) {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2));
    }

    /** 扁平版：用实色填充（2D 风格不要渐变，签名保持不变）。 */
    public static void roundGradient(Graphics2D g, double x, double y, double w, double h, double r,
                                     Color top, Color bottom, boolean vertical) {
        g.setColor(top);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2));
    }

    public static void strokeRound(Graphics2D g, double x, double y, double w, double h, double r,
                                   Color c, float lw) {
        g.setColor(c);
        g.setStroke(new BasicStroke(lw));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2));
    }

    public static void line(Graphics2D g, double x1, double y1, double x2, double y2, Color c, float lw) {
        g.setColor(c);
        g.setStroke(new BasicStroke(lw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) Math.round(x1), (int) Math.round(y1),
                (int) Math.round(x2), (int) Math.round(y2));
    }

    /** 线性渐变长条（用于判定线、血条等）。 */
    public static void gradientLine(Graphics2D g, double x, double y, double w, double h,
                                    Color left, Color mid, Color right) {
        double third = w / 3;
        g.setColor(left);
        g.fillRect((int) Math.round(x), (int) Math.round(y), (int) Math.round(third), (int) Math.round(h));
        g.setColor(mid);
        g.fillRect((int) Math.round(x + third), (int) Math.round(y), (int) Math.round(third), (int) Math.round(h));
        g.setColor(right);
        g.fillRect((int) Math.round(x + third * 2), (int) Math.round(y),
                (int) Math.round(w - third * 2), (int) Math.round(h));
    }

    // ------------------------------------------------------------------ 文字

    public static void text(Graphics2D g, String s, double x, double baseline, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, (float) x, (float) baseline);
    }

    public static void textC(Graphics2D g, String s, double cx, double baseline, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baseline);
    }

    public static void textR(Graphics2D g, String s, double rx, double baseline, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (float) (rx - fm.stringWidth(s)), (float) baseline);
    }

    public static double width(Graphics2D g, String s, Font f) {
        g.setFont(f);
        return g.getFontMetrics().stringWidth(s);
    }

    /** 带投影的文字，用于叠在图片上的标题。 */
    public static void textShadow(Graphics2D g, String s, double x, double baseline, Font f,
                                  Color c, Color shadow) {
        g.setFont(f);
        g.setColor(shadow);
        g.drawString(s, (float) x + 2, (float) baseline + 3);
        g.setColor(c);
        g.drawString(s, (float) x, (float) baseline);
    }

    public static void textShadowC(Graphics2D g, String s, double cx, double baseline, Font f,
                                   Color c, Color shadow) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double x = cx - fm.stringWidth(s) / 2.0;
        g.setColor(shadow);
        g.drawString(s, (float) x + 2, (float) baseline + 3);
        g.setColor(c);
        g.drawString(s, (float) x, (float) baseline);
    }

    /** 省略号截断到给定宽度。 */
    public static String ellipsis(Graphics2D g, String s, Font f, double maxWidth) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) return s;
        String ell = "…";
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (fm.stringWidth(s.substring(0, mid) + ell) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, Math.max(0, lo)) + ell;
    }

    // ------------------------------------------------------------------ 形状

    /**
     * 扁平风格下的"光斑"：半透明实心圆叠层，没有渐变。
     * 保留方法名是为了调用点不用改。
     */
    public static void glow(Graphics2D g, double cx, double cy, double r, Color c, int layers) {
        if (r <= 0.5 || c.getAlpha() <= 0) return;
        int[] alphas = {c.getAlpha(), (int) (c.getAlpha() * 0.45), (int) (c.getAlpha() * 0.2)};
        double[] radii = {r, r * 1.16, r * 1.36};
        for (int i = 0; i < alphas.length; i++) {
            double rr = radii[i];
            g.setColor(alpha(c, alphas[i]));
            g.fillOval((int) Math.round(cx - rr), (int) Math.round(cy - rr),
                    (int) Math.round(rr * 2), (int) Math.round(rr * 2));
        }
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** 2D 风格不压暗四角，改为画一圈内嵌细框，画面立刻"卡片化"。 */
    public static void vignette(Graphics2D g, int w, int h, int strength) {
        int inset = 16;
        g.setColor(Theme.alpha(Theme.LINE, 150));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(inset, inset, w - inset * 2, h - inset * 2);
    }

    /** 把图片按 cover 方式填满矩形。 */
    public static void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        if (img == null || w <= 0 || h <= 0) return;
        double iw = img.getWidth();
        double ih = img.getHeight();
        double s = Math.max(w / iw, h / ih);
        int sw = (int) Math.round(iw * s);
        int sh = (int) Math.round(ih * s);
        g.drawImage(img, x + (w - sw) / 2, y + (h - sh) / 2, sw, sh, null);
    }

    /**
     * 2D 面板：实色填充 + 描边 + 右下硬阴影，是整个 UI 的基础视觉单元。
     */
    public static void panel(Graphics2D g, double x, double y, double w, double h, double r,
                             Color fill) {
        panel(g, x, y, w, h, r, fill, Theme.LINE);
    }

    public static void panel(Graphics2D g, double x, double y, double w, double h, double r,
                             Color fill, Color border) {
        round(g, x + 6, y + 6, w, h, r, Theme.alpha(Theme.SHADOW, 190));
        round(g, x, y, w, h, r, fill);
        strokeRound(g, x, y, w, h, r, border, 2f);
    }

    /** 进度条（血条、加载条）。 */
    public static void bar(Graphics2D g, double x, double y, double w, double h, double ratio,
                           Color back, Color fill, Color edge) {
        round(g, x, y, w, h, h / 2, back);
        double rw = Math.max(0, Math.min(1, ratio)) * w;
        if (rw > 1) round(g, x, y, rw, h, Math.min(h / 2, rw / 2), fill);
        if (edge != null) strokeRound(g, x, y, w, h, h / 2, edge, 1f);
    }

    /** 垂直进度条（血条）。 */
    public static void vbar(Graphics2D g, double x, double y, double w, double h, double ratio,
                            Color back, Color fill, Color edge) {
        round(g, x, y, w, h, w / 2, back);
        double rh = Math.max(0, Math.min(1, ratio)) * h;
        if (rh > 1) round(g, x, y + (h - rh), w, rh, Math.min(w / 2, rh / 2), fill);
        if (edge != null) strokeRound(g, x, y, w, h, w / 2, edge, 1f);
    }
}
