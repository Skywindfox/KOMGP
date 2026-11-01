package komgp.dev;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * 生成发行包用的图标：256x256 PNG（Linux 桌面）+ ICO（Windows）。
 *
 * <p>ICO 直接用「PNG 负载」写法（Vista 以后支持），省掉引入图像库。
 */
public final class IconGen {

    private IconGen() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "packaging");
        Files.createDirectories(outDir);
        BufferedImage icon = draw(256);
        byte[] png = png(icon);
        Files.write(outDir.resolve("malody4k.png"), png);
        Files.write(outDir.resolve("malody4k.ico"), ico(png, 256));
        // 顺便给桌面环境一个小尺寸
        ImageIO.write(draw(128), "png", outDir.resolve("malody4k-128.png").toFile());
        System.out.println("图标已生成: " + outDir.toAbsolutePath());
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** 单张 PNG 的 ICO 容器。 */
    private static byte[] ico(byte[] png, int size) {
        ByteBuffer b = ByteBuffer.allocate(6 + 16 + png.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0);          // reserved
        b.putShort((short) 1);          // type = icon
        b.putShort((short) 1);          // count
        b.put((byte) (size >= 256 ? 0 : size));
        b.put((byte) (size >= 256 ? 0 : size));
        b.put((byte) 0);                // palette
        b.put((byte) 0);                // reserved
        b.putShort((short) 1);          // planes
        b.putShort((short) 32);         // bit count
        b.putInt(png.length);
        b.putInt(6 + 16);               // offset
        b.put(png);
        return b.array();
    }

    private static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double pad = s * 0.02;
        double r = s * 0.22;
        g.setColor(new Color(0x19, 0x16, 0x27));
        g.fill(new RoundRectangle2D.Double(pad, pad, s - pad * 2, s - pad * 2, r, r));
        g.setColor(new Color(0x4A, 0x3F, 0x6B));
        g.setStroke(new java.awt.BasicStroke((float) Math.max(2, s * 0.018)));
        g.draw(new RoundRectangle2D.Double(pad, pad, s - pad * 2, s - pad * 2, r, r));

        // 四条轨道
        double laneW = s * 0.17;
        double gap = s * 0.012;
        double totalW = laneW * 4 + gap * 3;
        double x0 = (s - totalW) / 2;
        double top = s * 0.14;
        double bottom = s * 0.80;
        Color[] cols = {
                new Color(0x7F, 0xD4, 0xC1), new Color(0xB3, 0x9D, 0xDB),
                new Color(0xFF, 0xD1, 0x66), new Color(0xFF, 0x9E, 0x64)
        };
        for (int i = 0; i < 4; i++) {
            double x = x0 + i * (laneW + gap);
            g.setColor(new Color(0x2C, 0x25, 0x42));
            g.fill(new RoundRectangle2D.Double(x, top, laneW, bottom - top, laneW * 0.14, laneW * 0.14));
            g.setColor(new Color(cols[i].getRed(), cols[i].getGreen(), cols[i].getBlue(), 70));
            g.fill(new RoundRectangle2D.Double(x + 2, top + 2, laneW - 4, bottom - top - 4,
                    laneW * 0.12, laneW * 0.12));
        }

        // 音符（中间两条轨道各一个）
        double noteH = s * 0.085;
        drawNote(g, x0 + laneW + gap, s * 0.30, laneW, noteH, cols[1]);
        drawNote(g, x0 + 2 * (laneW + gap), s * 0.46, laneW, noteH, cols[2]);
        drawNote(g, x0, s * 0.63, laneW, noteH, cols[0]);

        // 判定线
        double y = bottom - s * 0.03;
        g.setColor(new Color(0x7F, 0xD4, 0xC1));
        g.fill(new RoundRectangle2D.Double(x0, y, totalW, s * 0.026, s * 0.013, s * 0.013));

        g.dispose();
        return img;
    }

    private static void drawNote(Graphics2D g, double x, double y, double w, double h, Color c) {
        g.setColor(new Color(0x10, 0x0D, 0x1B));
        g.fill(new RoundRectangle2D.Double(x - 2, y + 2, w + 4, h + 4, h * 0.3, h * 0.3));
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, h * 0.3, h * 0.3));
        g.setColor(new Color(255, 255, 255, 170));
        g.fill(new RoundRectangle2D.Double(x + w * 0.10, y + h * 0.16, w * 0.80, h * 0.22,
                h * 0.11, h * 0.11));
    }
}
