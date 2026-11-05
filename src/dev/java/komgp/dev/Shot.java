package komgp.dev;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import komgp.cfg.ChartLibrary;
import komgp.core.Chart;
import komgp.core.JudgeWindow;
import komgp.core.PlayState;
import komgp.game.GameApp;
import komgp.tools.DemoGen;
import komgp.render.Painter;
import komgp.ui.PlayScreen;
import komgp.ui.ResultScreen;
import komgp.ui.SettingsScreen;
import komgp.ui.SongSelectScreen;
import komgp.ui.TitleScreen;
import komgp.util.Log;

/**
 * 开发工具：把任意界面渲染成 PNG，用于离线检查版式（无需真实窗口）。
 *
 * <pre>
 *   java komgp.tools.Shot --screen play --time 24000 --out shots/play.png
 * </pre>
 */
public final class Shot {

    private Shot() {}

    public static void main(String[] args) throws Exception {
        Path base = Path.of(".");
        String screenName = "title";
        Path out = Path.of("shots/shot.png");
        int w = 1920;
        int h = 1080;
        double time = 26000;
        double quality = 16;
        String chartName = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--screen" -> screenName = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--w" -> w = Integer.parseInt(args[++i]);
                case "--h" -> h = Integer.parseInt(args[++i]);
                case "--time" -> time = Double.parseDouble(args[++i]);
                case "--quality" -> quality = Double.parseDouble(args[++i]);
                case "--chart" -> chartName = args[++i];
                case "--charts" -> base = Path.of(args[++i]);
                default -> { }
            }
        }

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("sun.java2d.uiScale", "1.0");

        GameApp app = new GameApp(base);
        app.library.scan();
        if (app.library.items.isEmpty()) {
            Log.warn("曲库为空，先生成演示谱面");
            DemoGen.generateAll(app.chartsDir);
            app.library.scan();
        }

        ChartLibrary.Item item = chartName == null ? app.library.items.get(0) : app.library.findByName(chartName);
        if (item == null) item = app.library.items.get(0);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        double scale = Math.min(w / 1280.0, h / 720.0);
        Painter.setDevice(w, h, scale, (w - 1280 * scale) / 2, (h - 720 * scale) / 2);
        g.translate((w - 1280 * scale) / 2, (h - 720 * scale) / 2);
        g.scale(scale, scale);
        g.setClip(0, 0, 1280, 720);
        Painter.quality(g);

        switch (screenName) {
            case "title" -> {
                TitleScreen s = new TitleScreen(app);
                s.update(16, 1600);
                s.render(g);
            }
            case "select" -> {
                SongSelectScreen s = new SongSelectScreen(app);
                for (int i = 0; i < 30; i++) s.update(16, i * 16.0);
                s.render(g);
            }
            case "settings" -> {
                SettingsScreen s = new SettingsScreen(app, null);
                s.render(g);
            }
            case "play" -> {
                PlayScreen s = new PlayScreen(app, item);
                PlayState ps = simulate(item.chart, time, quality);
                s.renderPreview(g, ps, time);
            }
            case "countdown" -> {
                PlayScreen s = new PlayScreen(app, item);
                PlayState ps = new PlayState(item.chart, JudgeWindow.standard(), false, false);
                s.renderPreview(g, ps, -900);
            }
            case "hold" -> {
                // 固定画面：找一个长条，按住它，渲染在"正在按住"的瞬间
                komgp.core.Chart ch = item.chart;
                int hi = -1;
                for (int i = 0; i < ch.noteCount(); i++) {
                    if (ch.hold[i]) { hi = i; break; }
                }
                if (hi < 0) {
                    Log.warn("这张谱面没有长条");
                    break;
                }
                double headT = ch.time[hi];
                PlayState ps = new PlayState(ch, JudgeWindow.standard(), false, false);
                for (double t = 0; t <= headT - 1; t += 5) ps.update(t);
                ps.press(ch.column[hi], headT);
                double renderAt = headT + (ch.endTime[hi] - headT) * 0.45;
                for (double t = headT; t <= renderAt; t += 5) ps.update(t);
                Log.info(String.format("长条: 列%d %.0f->%.0fms, 渲染于 %.0fms, 正在按住=%s",
                        ch.column[hi], headT, ch.endTime[hi], renderAt, ps.isHolding(hi)));
                PlayScreen s = new PlayScreen(app, item);
                s.renderPreview(g, ps, renderAt);
            }
            case "editor-new" -> {
                komgp.ui.EditorScreen s = new komgp.ui.EditorScreen(app, null);
                s.onEnter();
                Thread.sleep(400);
                s.update(16, 1000);
                s.render(g);
            }
            case "editor" -> {
                komgp.ui.EditorScreen s = new komgp.ui.EditorScreen(app, item.path);
                s.onEnter();
                Thread.sleep(1600);
                s.update(16, 1000);
                s.render(g);
            }
            case "result" -> {
                PlayState ps = simulate(item.chart, item.chart.lastNoteMs + 500, quality);
                ps.finalizePending();
                ResultScreen s = new ResultScreen(app, item, ps, false);
                s.update(0, 0);
                s.render(g);
            }
            default -> Log.warn("未知界面: " + screenName);
        }

        g.dispose();
        Path dir = out.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        ImageIO.write(img, "png", out.toFile());
        Log.info("截图已保存: " + out.toAbsolutePath() + " (" + w + "x" + h + ")");
    }

    /** 模拟一个「普通玩家」的击打，得到接近真实的判定分布。 */
    public static PlayState simulate(Chart c, double uptoMs, double sigmaMs) {
        PlayState ps = new PlayState(c, JudgeWindow.standard(), false, false);
        Random rnd = new Random(2024);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < c.noteCount(); i++) {
            if (c.time[i] > uptoMs) break;
            order.add(i);
        }
        for (int i : order) {
            double nt = c.time[i];
            boolean drop = rnd.nextDouble() < 0.035;
            if (drop) continue;
            double off = rnd.nextGaussian() * sigmaMs;
            if (rnd.nextDouble() < 0.02) off += 90; // 偶尔来个大失误
            ps.press(c.column[i], nt + off);
            if (!c.hold[i] || rnd.nextDouble() < 0.15) {
                ps.release(c.column[i], nt + off + 60);
            }
        }
        for (double t = 0; t <= uptoMs; t += 6) {
            ps.update(t);
        }
        ps.update(uptoMs);
        return ps;
    }
}
