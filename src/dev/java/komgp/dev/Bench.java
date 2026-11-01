package komgp.dev;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;

import komgp.cfg.ChartLibrary;
import komgp.core.PlayState;
import komgp.game.GameApp;
import komgp.tools.DemoGen;
import komgp.render.Painter;
import komgp.ui.PlayScreen;
import komgp.util.Log;

/**
 * 渲染基准：离屏渲染 N 帧游玩画面，输出平均 / P99 耗时，用来量化优化效果。
 *
 * <pre>
 *   java -cp ... komgp.tools.Bench [帧数] [宽] [高]
 * </pre>
 */
public final class Bench {

    private Bench() {}

    public static void main(String[] args) throws Exception {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        int w = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
        int h = args.length > 2 ? Integer.parseInt(args[2]) : 1080;

        System.setProperty("sun.java2d.uiScale", "1.0");
        GameApp app = new GameApp(Path.of("."));
        app.library.scan();
        if (app.library.items.isEmpty()) {
            DemoGen.generateAll(app.chartsDir);
            app.library.scan();
        }
        ChartLibrary.Item item = app.library.findByName("Hard");
        if (item == null) item = app.library.items.get(0);

        PlayScreen screen = new PlayScreen(app, item);
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

        komgp.util.Prof.enabled = true;
        // 预热：JIT + 首次贴图生成
        for (int i = 0; i < 120; i++) {
            screen.renderPreview(g, ps, t + i * 16);
        }

        long[] times = new long[frames];
        int visibleSum = 0;
        for (int i = 0; i < frames; i++) {
            long t0 = System.nanoTime();
            screen.renderPreview(g, ps, t + i * 16.6);
            times[i] = System.nanoTime() - t0;
            visibleSum += visibleNotes(ps, t + i * 16.6);
        }
        g.dispose();

        Arrays.sort(times);
        double avg = 0;
        for (long v : times) avg += v;
        avg /= frames;
        long p50 = times[frames / 2];
        long p99 = times[(int) Math.min(frames - 1, frames * 0.99)];
        long max = times[frames - 1];

        System.out.println();
        System.out.printf("渲染基准: %.0fx%.0f 逻辑(缩放 %.2fx), %d 帧, 谱面 %s [%s] %d notes%n",
                1280.0, 720.0, scale, frames, item.chart.title, item.chart.difficulty,
                item.chart.noteCount());
        System.out.printf("  平均 %.2f ms  (%.0f fps)%n", avg / 1e6, 1e9 / avg);
        System.out.printf("  P50  %.2f ms%n", p50 / 1e6);
        System.out.printf("  P99  %.2f ms%n", p99 / 1e6);
        System.out.printf("  最慢 %.2f ms%n", max / 1e6);
        System.out.printf("  平均可见音符 %.1f 个%n", visibleSum / (double) frames);
        Log.info("java " + System.getProperty("java.version") + " / "
                + System.getProperty("os.name"));
    }

    private static int visibleNotes(PlayState ps, double now) {
        int i = ps.firstVisibleFrom(now);
        int n = 0;
        for (; i < ps.chart.noteCount(); i++) {
            if (ps.chart.time[i] > now + 1500) break;
            n++;
        }
        return n;
    }
}
