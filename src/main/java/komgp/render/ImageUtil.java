package komgp.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** 图片解码/裁剪小工具。 */
public final class ImageUtil {

    private ImageUtil() {}

    public static BufferedImage decode(byte[] raw) {
        if (raw == null || raw.length < 8) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 居中裁成正方形，并限制最大边长。 */
    public static BufferedImage square(BufferedImage src, int max) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int out = Math.max(8, Math.min(max, side));
        BufferedImage dst = new BufferedImage(out, out, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, out, out, (w - side) / 2, (h - side) / 2,
                (w - side) / 2 + side, (h - side) / 2 + side, null);
        g.dispose();
        return dst;
    }
}
