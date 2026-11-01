package komgp.dev;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import komgp.cfg.ChartLibrary;
import komgp.core.PlayState;
import komgp.game.GameApp;
import komgp.render.Backdrop;
import komgp.render.Painter;
import komgp.render.Playfield;
import komgp.ui.PlayScreen;

/** 逐段渲染耗时分析。 */
public final class Prof {
    public static void main(String[] a) {
        int w = a.length > 0 ? Integer.parseInt(a[0]) : 1920;
        int h = a.length > 1 ? Integer.parseInt(a[1]) : 1080;
        GameApp app = new GameApp(Path.of("."));
        app.library.scan();
        ChartLibrary.Item item = app.library.findByName("Hard");
        PlayScreen screen = new PlayScreen(app, item);
        Playfield field = new Playfield(item.chart.columns);
        double t = 20000;
        PlayState ps = Shot.simulate(item.chart, t, 18);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        double scale = Math.min(w / 1280.0, h / 720.0);
        Painter.setDevice(w, h, scale, (w - 1280 * scale) / 2, (h - 720 * scale) / 2);
        g.translate((w - 1280 * scale) / 2, (h - 720 * scale) / 2);
        g.scale(scale, scale);
        g.setClip(0, 0, 1280, 720);
        Painter.quality(g);
        BufferedImage bg = Backdrop.of(app.library.backdrop(item), 1280, 720, 0.55, 2.2);
        double pxx = 0.4 * 2.6;

        for (int i = 0; i < 60; i++) { g.drawImage(bg, 0, 0, 1280, 720, null); field.drawDynamic(g, ps, t, pxx); field.drawNotes(g, ps, t, pxx, 0, true); }

        time("背景 blit", () -> g.drawImage(bg, 0, 0, 1280, 720, null));
        time("动态轨道层", () -> field.drawDynamic(g, ps, t, pxx));
        time("音符", () -> field.drawNotes(g, ps, t, pxx, 0, true));
        time("判定线", () -> field.drawJudgeLine(g, 0.5));
        time("命中特效", () -> field.drawEffects(g, ps, t, true));
        time("整帧 PlayScreen", () -> screen.renderPreview(g, ps, t));

        // 关键原语
        time("Painter.round 大矩形", () -> Painter.round(g, 100, 100, 600, 600, 18, java.awt.Color.RED));
        time("Painter.glow r=200", () -> Painter.glow(g, 640, 360, 200, new java.awt.Color(90, 209, 255, 60), 3));
        time("RadialGradientPaint r=200", () -> {
            g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(640, 360), 200,
                    new float[]{0f, 0.4f, 1f}, new java.awt.Color[]{java.awt.Color.RED, java.awt.Color.BLUE, java.awt.Color.BLACK},
                    java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE));
            g.fillOval(440, 160, 400, 400);
        });
        time("文字 30 次", () -> {
            for (int i = 0; i < 30; i++)
                Painter.text(g, "PERFECT+ 12345", 40, 40 + i * 18, komgp.render.Theme.body(), java.awt.Color.WHITE);
        });
        System.out.println("scale=" + scale + " size=" + w + "x" + h);
    }

    private static void time(String name, Runnable r) {
        for (int i = 0; i < 30; i++) r.run();
        long t0 = System.nanoTime();
        int n = 60;
        for (int i = 0; i < n; i++) r.run();
        System.out.printf("%-22s %8.3f ms%n", name, (System.nanoTime() - t0) / 1e6 / n);
    }
}
