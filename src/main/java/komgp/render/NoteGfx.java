package komgp.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * 预渲染的音符贴图 —— 2D 扁平版本。
 *
 * <p>一个音符 = 纯色块 + 深色描边 + 顶部亮条，没有渐变和发光。
 * 既符合 2D 画风，也依旧保持「一帧几百个 drawImage 的 1:1 拷贝」这个性能优势。
 */
public final class NoteGfx {

    public static final double NOTE_W = Theme.LANE_W - Theme.NOTE_INSET * 2;

    private static int key = -1;
    private static BufferedImage[] tap = new BufferedImage[0];
    private static BufferedImage[] head = new BufferedImage[0];
    private static BufferedImage[] glow = new BufferedImage[0];
    private static BufferedImage missMark;

    private NoteGfx() {}

    public static void ensure(double scale, int columns) {
        int k = (int) Math.round(scale * 1000) * 16 + columns;
        if (k == key && tap.length == columns) return;
        key = k;
        build(Math.max(0.5, scale), columns);
    }

    public static BufferedImage tap(int col) {
        return tap[Math.floorMod(col, tap.length)];
    }

    public static BufferedImage head(int col) {
        return head[Math.floorMod(col, head.length)];
    }

    public static BufferedImage glow(int col) {
        return glow[Math.floorMod(col, glow.length)];
    }

    public static BufferedImage missMark() {
        return missMark;
    }

    private static void build(double s, int columns) {
        tap = new BufferedImage[columns];
        head = new BufferedImage[columns];
        glow = new BufferedImage[columns];
        for (int c = 0; c < columns; c++) {
            Color accent = Theme.laneColor(c);
            tap[c] = sprite(s, accent, false);
            head[c] = sprite(s, accent, true);
            glow[c] = glowSprite(s, accent);
        }
        missMark = missSprite(s);
    }

    private static Graphics2D create(BufferedImage img, double s) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(s, s);
        return g;
    }

    private static BufferedImage sprite(double s, Color accent, boolean isHead) {
        int w = (int) Math.ceil((NOTE_W + 4) * s);
        int h = (int) Math.ceil((Theme.NOTE_H + 4) * s);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = create(img, s);
        double W = NOTE_W;
        double H = Theme.NOTE_H;
        double r = 6;
        double ox = 2;
        double oy = 2;

        // 硬阴影（右下偏移，2D 贴纸感）
        g.setColor(Theme.alpha(Theme.SHADOW, 170));
        g.fill(new RoundRectangle2D.Double(ox + 2, oy + 3, W, H, r * 2, r * 2));

        // 主体纯色
        g.setColor(accent);
        g.fill(new RoundRectangle2D.Double(ox, oy, W, H, r * 2, r * 2));

        // 顶部亮条
        g.setColor(Theme.mix(accent, Color.WHITE, 0.55f));
        g.fill(new RoundRectangle2D.Double(ox + 4, oy + 3, W - 8, 4, 4, 4));

        // 描边
        g.setColor(Theme.alpha(Theme.SHADOW, 235));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(ox + 1, oy + 1, W - 2, H - 2, r * 2, r * 2));

        // 长条头：中间一条深色缝，和条身区分
        if (isHead) {
            g.setColor(Theme.alpha(Theme.SHADOW, 90));
            g.fill(new RoundRectangle2D.Double(ox + 10, oy + H / 2 - 2, W - 20, 4, 4, 4));
        }

        g.dispose();
        return img;
    }

    /** 命中特效：扁平圆环（不是发光）。 */
    private static BufferedImage glowSprite(double s, Color accent) {
        int size = (int) Math.ceil(Theme.LANE_W * 1.2 * s);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(s, s);
        double c = Theme.LANE_W * 0.6;
        g.setColor(Theme.alpha(accent, 70));
        g.fillOval((int) (c - Theme.LANE_W * 0.5), (int) (c - Theme.LANE_W * 0.5),
                (int) Theme.LANE_W, (int) Theme.LANE_W);
        g.setColor(Theme.alpha(accent, 150));
        g.setStroke(new BasicStroke(3f));
        int d = (int) (Theme.LANE_W * 0.72);
        g.drawOval((int) (c - d / 2.0), (int) (c - d / 2.0), d, d);
        g.dispose();
        return img;
    }

    private static BufferedImage missSprite(double s) {
        int size = (int) Math.ceil(24 * s);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = create(img, s);
        g.setColor(Theme.DANGER);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.drawLine(4, 4, 20, 20);
        g.drawLine(20, 4, 4, 20);
        g.dispose();
        return img;
    }
}
