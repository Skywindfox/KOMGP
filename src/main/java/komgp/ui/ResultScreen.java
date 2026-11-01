package komgp.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import komgp.cfg.ChartLibrary;
import komgp.core.Chart;
import komgp.core.Judge;
import komgp.core.PlayState;
import komgp.game.GameApp;
import komgp.game.Screen;
import komgp.render.Backdrop;
import komgp.render.Painter;
import komgp.render.Theme;

/** 结算页。 */
public final class ResultScreen extends Screen {

    private final ChartLibrary.Item item;
    private final PlayState ps;
    private final boolean failed;
    private final List<Ui.Button> buttons = new ArrayList<>();
    private BufferedImage bg;
    private boolean newRecord;

    public ResultScreen(GameApp app, ChartLibrary.Item item, PlayState ps, boolean failed) {
        super(app);
        this.item = item;
        this.ps = ps;
        this.failed = failed;
        long best = app.settings.bestFor(item.id);
        this.newRecord = !failed && ps.score() >= best && ps.score() > 0;
        double w = 150;
        double y = 632;
        buttons.add(new Ui.Button(Theme.W / 2.0 - w * 1.5 - 20, y, w, 46,
                "重试 (Enter)", () -> app.go(new PlayScreen(app, item))));
        buttons.add(new Ui.Button(Theme.W / 2.0 - w / 2, y, w, 46,
                "选曲 (Esc)", () -> app.go(new SongSelectScreen(app))));
        buttons.add(new Ui.Button(Theme.W / 2.0 + w / 2 + 20, y, w, 46,
                "主菜单", () -> app.go(new TitleScreen(app))));
    }

    @Override
    public void keyPressed(int code, double songMs) {
        switch (code) {
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_R ->
                    app.go(new PlayScreen(app, item));
            case KeyEvent.VK_ESCAPE -> app.go(new SongSelectScreen(app));
            default -> { }
        }
    }

    @Override
    public void mousePressed(double x, double y, int button) {
        for (Ui.Button b : buttons) if (b.hit(x, y)) { b.action.run(); return; }
    }

    @Override
    public void update(double dt, double wallMs) {
        if (bg == null || !Backdrop.matches(bg)) {
            bg = Backdrop.ofDevice(app.library.cover(item), failed ? 0.88 : 0.80, 2.6);
        }
    }

    @Override
    public void render(Graphics2D g) {
        if (bg != null) Painter.blitExact(g, bg, 0, 0);
        else {
            g.setColor(Theme.BG);
            g.fillRect(0, 0, Theme.W, Theme.H);
        }
        Painter.vignette(g, Theme.W, Theme.H, 110);

        Chart c = item.chart;
        double px = 96;
        double py = 64;
        double pw = Theme.W - 192;
        double ph = 540;
        Painter.panel(g, px, py, pw, ph, 18, Theme.BG2);
        Painter.round(g, px + 26, py + 20, 90, 5, 2.5, Theme.ACCENT);

        Painter.text(g, Painter.ellipsis(g, c.displayTitle(), Theme.f(22, true), 520),
                px + 32, py + 46, Theme.f(22, true), Theme.TEXT);
        Painter.text(g, c.displayArtist() + "   ·   " + (c.difficulty.isBlank() ? "谱面" : c.difficulty)
                        + "   ·   Lv." + c.levelLabel(),
                px + 32, py + 72, Theme.small(), Theme.MUTED);

        // 评级
        String grade = failed ? "F" : ps.grade();
        Color gc = switch (grade) {
            case "SS" -> Theme.GOLD;
            case "S" -> Theme.ACCENT;
            case "A" -> Theme.OK;
            case "B" -> new Color(140, 190, 255);
            case "C" -> Theme.WARN;
            case "D" -> Theme.MUTED;
            default -> Theme.DANGER;
        };
        double gx = px + 180;
        double gy = py + 240;
        Painter.round(g, gx - 86, gy - 92, 172, 172, 26, Theme.alpha(Theme.SHADOW, 150));
        Painter.strokeRound(g, gx - 90, gy - 96, 172, 172, 26, Theme.alpha(gc, 90), 3f);
        Painter.textC(g, grade, gx, gy + 40, Theme.f(140, true), gc);

        if (!failed && (ps.allPerfect() || ps.fullCombo())) {
            String tag = ps.allPerfect() ? "ALL PERFECT" : "FULL COMBO";
            Color tc = ps.allPerfect() ? Theme.GOLD : Theme.OK;
            Painter.round(g, gx - 80, gy + 66, 160, 30, 8, Theme.alpha(tc, 40));
            Painter.strokeRound(g, gx - 80, gy + 66, 160, 30, 8, Theme.alpha(tc, 180), 1f);
            Painter.textC(g, tag, gx, gy + 87, Theme.f(13, true), tc);
        }

        // 分数
        Painter.textC(g, String.format("%,d", ps.score()), gx, gy + 150, Theme.f(40, true), Theme.TEXT);
        Painter.textC(g, String.format("准确率 %.2f%%", ps.accuracy() * 100), gx, gy + 180,
                Theme.body(), Theme.ACCENT);
        if (newRecord && !failed) {
            Painter.textC(g, "NEW RECORD", gx, gy + 208, Theme.f(13, true), Theme.GOLD);
        }

        // 右侧判定统计
        double rx = px + 420;
        double ry = py + 96;
        Judge[] order = {Judge.PERFECT_PLUS, Judge.PERFECT, Judge.EARLY, Judge.LATE, Judge.BAD, Judge.MISS};
        int total = Math.max(1, ps.noteCount());
        Painter.text(g, "判定统计", rx, ry - 26, Theme.f(16, true), Theme.TEXT);
        for (Judge j : order) {
            int n = ps.count[j.ordinal()];
            Painter.text(g, j.label, rx, ry, Theme.f(13, true), Theme.judgeColor(j));
            Painter.textR(g, String.valueOf(n), rx + 200, ry, Theme.f(16, true), Theme.TEXT);
            double ratio = n / (double) total;
            Painter.round(g, rx + 216, ry - 10, 190, 8, 4, new Color(40, 50, 74, 220));
            Painter.round(g, rx + 216, ry - 10, Math.max(2, 190 * ratio), 8, 4,
                    Theme.alpha(Theme.judgeColor(j), 220));
            Painter.text(g, String.format("%.1f%%", ratio * 100), rx + 420, ry,
                    Theme.tiny(), Theme.DIM);
            ry += 34;
        }

        ry += 10;
        Painter.fill(g, rx, ry - 20, 520, 1, new Color(90, 118, 160, 70));
        Painter.text(g, "最大连击", rx, ry + 8, Theme.small(), Theme.DIM);
        Painter.textR(g, String.valueOf(ps.maxCombo), rx + 200, ry + 8, Theme.f(16, true), Theme.TEXT);
        Painter.text(g, "音符总数", rx, ry + 34, Theme.small(), Theme.DIM);
        Painter.textR(g, String.valueOf(ps.noteCount()), rx + 200, ry + 34, Theme.f(16, true), Theme.TEXT);
        Painter.text(g, "平均偏差", rx, ry + 60, Theme.small(), Theme.DIM);
        Painter.textR(g, String.format("%+.1f ms", ps.meanOffset()), rx + 200, ry + 60,
                Theme.f(16, true), Math.abs(ps.meanOffset()) > 20 ? Theme.WARN : Theme.TEXT);
        Painter.text(g, "断条", rx, ry + 86, Theme.small(), Theme.DIM);
        Painter.textR(g, String.valueOf(ps.holdBreaks), rx + 200, ry + 86,
                Theme.f(16, true), ps.holdBreaks > 0 ? Theme.WARN : Theme.TEXT);

        // 偏移建议
        double mean = ps.meanOffset();
        if (ps.offsetCount > 20 && Math.abs(mean) > 8) {
            String advice = String.format("建议：把本曲延时调整为 %+.0f ms（当前 %+.0f ms）",
                    app.settings.offsetFor(item.id) - mean, app.settings.offsetFor(item.id));
            Painter.text(g, advice, rx, ry + 128, Theme.small(), Theme.ACCENT);
            Painter.text(g, "在选曲页按 [ ] 即可调整，游戏内也能调整", rx, ry + 150,
                    Theme.tiny(), Theme.DIM);
        } else {
            Painter.text(g, "偏差控制得不错，可以尝试提高下落速度或挑战更高难度", rx, ry + 128,
                    Theme.small(), Theme.MUTED);
        }

        for (Ui.Button b : buttons) b.draw(g, false, mouseX, mouseY);
        Painter.textC(g, "Enter 重试   ·   Esc 返回选曲", Theme.W / 2.0, 706, Theme.tiny(), Theme.DIM);
    }
}
