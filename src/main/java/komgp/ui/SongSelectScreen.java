package komgp.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import komgp.cfg.ChartLibrary;
import komgp.core.Chart;
import komgp.game.GameApp;
import komgp.game.Screen;
import komgp.render.Backdrop;
import komgp.render.Painter;
import komgp.render.Theme;

/** 选曲页：左侧歌曲列表，右侧曲绘 + 难度 + 单曲延时。 */
public final class SongSelectScreen extends Screen {

    private static final double LIST_X = 40;
    private static final double LIST_Y = 100;
    private static final double LIST_W = 468;
    private static final double LIST_H = 566;
    private static final double ROW_H = 68;
    private static final int VISIBLE = (int) (LIST_H / ROW_H);

    private int songIdx;
    private int diffIdx;
    private double scroll;
    private double scrollTarget;

    private BufferedImage bg;
    private String bgId;

    private final List<Ui.Button> buttons = new ArrayList<>();
    private final List<double[]> diffRects = new ArrayList<>();
    private String densityId;
    private double[] density = new double[0];

    public SongSelectScreen(GameApp app) {
        super(app);
        restore();
        buttons.add(new Ui.Button(1180 - 130, 674, 120, 40, "返回", () -> back()));
        buttons.add(new Ui.Button(1180 - 264, 674, 120, 40, "开始", this::start));
    }

    private void restore() {
        String last = app.settings.lastChart;
        List<ChartLibrary.Song> songs = app.library.songs;
        for (int i = 0; i < songs.size(); i++) {
            for (int d = 0; d < songs.get(i).items.size(); d++) {
                if (songs.get(i).items.get(d).id.equals(last)) {
                    songIdx = i;
                    diffIdx = d;
                    return;
                }
            }
        }
        songIdx = 0;
        diffIdx = 0;
    }

    private ChartLibrary.Song song() {
        List<ChartLibrary.Song> songs = app.library.songs;
        if (songs.isEmpty()) return null;
        songIdx = Math.max(0, Math.min(songs.size() - 1, songIdx));
        return songs.get(songIdx);
    }

    private ChartLibrary.Item item() {
        ChartLibrary.Song s = song();
        if (s == null) return null;
        diffIdx = Math.max(0, Math.min(s.items.size() - 1, diffIdx));
        return s.items.get(diffIdx);
    }

    private void back() {
        app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
        app.go(new TitleScreen(app));
    }

    private void start() {
        ChartLibrary.Item it = item();
        if (it == null) return;
        app.sfx.play(komgp.audio.Sfx.Kind.OK);
        app.settings.lastChart = it.id;
        app.go(new PlayScreen(app, it));
    }

    private void move(int d) {
        ChartLibrary.Song s = song();
        if (s == null) return;
        int n = app.library.songs.size();
        songIdx = Math.floorMod(songIdx + d, n);
        diffIdx = 0;
        app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
    }

    private void moveDiff(int d) {
        ChartLibrary.Song s = song();
        if (s == null) return;
        diffIdx = Math.floorMod(diffIdx + d, s.items.size());
        app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
    }

    private void nudgeOffset(double delta) {
        ChartLibrary.Item it = item();
        if (it == null) return;
        double cur = app.settings.offsetFor(it.id);
        app.settings.setOffsetFor(it.id, cur + delta);
        app.saveSettings();
        app.toast(String.format("本曲延时 %+.0f ms", app.settings.offsetFor(it.id)));
        app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
    }

    @Override
    public void keyPressed(int code, double songMs) {
        switch (code) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> move(-1);
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> move(1);
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> moveDiff(-1);
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> moveDiff(1);
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> start();
            case KeyEvent.VK_ESCAPE -> back();
            case KeyEvent.VK_F11 -> app.setFullscreen(!app.isFullscreen());
            case KeyEvent.VK_R -> {
                app.library.scan();
                app.toast("曲库已刷新：" + app.library.items.size() + " 张谱面");
                songIdx = Math.min(songIdx, Math.max(0, app.library.songs.size() - 1));
            }
            case KeyEvent.VK_E -> {
                ChartLibrary.Item it = item();
                app.sfx.play(komgp.audio.Sfx.Kind.OK);
                app.go(new EditorScreen(app, it == null ? null : it.path));
            }
            case KeyEvent.VK_P -> {
                app.settings.practiceMode = !app.settings.practiceMode;
                app.settings.save();
                app.toast("练习模式：" + (app.settings.practiceMode ? "开（判定 ×1.5，不死）" : "关"));
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_OPEN_BRACKET -> nudgeOffset(-1);
            case KeyEvent.VK_CLOSE_BRACKET -> nudgeOffset(1);
            case KeyEvent.VK_COMMA -> nudgeOffset(-10);
            case KeyEvent.VK_PERIOD -> nudgeOffset(10);
            default -> { }
        }
    }

    @Override
    public void mousePressed(double x, double y, int button) {
        for (Ui.Button b : buttons) {
            if (b.hit(x, y)) {
                b.action.run();
                return;
            }
        }
        if (x >= LIST_X && x <= LIST_X + LIST_W && y >= LIST_Y && y <= LIST_Y + LIST_H) {
            int row = (int) ((y - LIST_Y + scroll) / ROW_H);
            if (row >= 0 && row < app.library.songs.size() && row != songIdx) {
                songIdx = row;
                diffIdx = 0;
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
            }
            return;
        }
        for (int i = 0; i < diffRects.size(); i++) {
            double[] r = diffRects.get(i);
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                if (i == diffIdx) start();
                else {
                    diffIdx = i;
                    app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
                }
                return;
            }
        }
    }

    @Override
    public void wheel(int notches) {
        move(notches > 0 ? 1 : -1);
    }

    @Override
    public void update(double dt, double wallMs) {
        ChartLibrary.Song s = song();
        if (s == null) return;
        double target = songIdx * ROW_H - (VISIBLE - 1) * ROW_H / 2.0;
        scrollTarget = Math.max(0, Math.min(target, Math.max(0, app.library.songs.size() * ROW_H - LIST_H)));
        scroll += (scrollTarget - scroll) * Math.min(1, dt / 90.0);
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(Graphics2D g) {
        ChartLibrary.Item it = item();
        if (it != null) {
            if (!it.id.equals(bgId) || !Backdrop.matches(bg)) {
                BufferedImage cover = app.library.cover(it);
                bg = Backdrop.ofDevice(cover, 0.78, 2.2);
                bgId = it.id;
            }
            if (bg != null) Painter.blitExact(g, bg, 0, 0);
        } else {
            g.setColor(Theme.BG);
            g.fillRect(0, 0, Theme.W, Theme.H);
        }
        Painter.vignette(g, Theme.W, Theme.H, 70);

        // 顶部
        Painter.text(g, "选曲", 44, 62, Theme.f(30, true), Theme.TEXT);
        Painter.text(g, "↑↓ 切歌  ←→ 换难度  Enter 开始  E 编辑器  [ ] 单曲延时  P 练习模式  R 刷新",
                130, 60, Theme.tiny(), Theme.MUTED);
        Painter.fill(g, 40, 78, Theme.W - 80, 1, new Color(120, 160, 220, 60));

        if (app.library.songs.isEmpty()) {
            Painter.textC(g, "曲库是空的：把 .mc / .mcz 谱面放进 charts/ 目录后按 R 刷新",
                    Theme.W / 2.0, Theme.H / 2.0, Theme.h2(), Theme.WARN);
            Painter.textC(g, "Esc 返回", Theme.W / 2.0, Theme.H / 2.0 + 40, Theme.body(), Theme.DIM);
            return;
        }

        drawList(g);
        drawDetail(g, it);

        for (Ui.Button b : buttons) b.draw(g, false, mouseX, mouseY);
    }

    private void drawList(Graphics2D g) {
        Ui.panel(g, LIST_X, LIST_Y, LIST_W, LIST_H);
        g.setClip((int) LIST_X + 2, (int) LIST_Y + 2, (int) LIST_W - 4, (int) LIST_H - 4);
        List<ChartLibrary.Song> songs = app.library.songs;
        double y = LIST_Y + 8 - scroll;
        for (int i = 0; i < songs.size(); i++) {
            ChartLibrary.Song s = songs.get(i);
            boolean sel = i == songIdx;
            if (y + ROW_H > LIST_Y - 10 && y < LIST_Y + LIST_H + 10) {
                if (sel) {
                    Painter.round(g, LIST_X + 8, y, LIST_W - 16, ROW_H - 6, 10,
                            Theme.mix(Theme.BG2, Theme.ACCENT, 0.28f));
                    Painter.strokeRound(g, LIST_X + 8, y, LIST_W - 16, ROW_H - 6, 10,
                            Theme.ACCENT, 2f);
                    Painter.round(g, LIST_X + 8, y + 8, 4, ROW_H - 22, 2, Theme.ACCENT);
                }
                ChartLibrary.Item thumb = s.first();
                int tx = (int) (LIST_X + 22);
                int ty = (int) (y + 10);
                Painter.round(g, tx, ty, ROW_H - 26, ROW_H - 26, 8, new Color(8, 10, 18, 200));
                g.drawImage(app.library.cover(thumb), tx, ty, (int) (ROW_H - 26), (int) (ROW_H - 26), null);

                Font tf = Theme.f(19, sel);
                Painter.text(g, Painter.ellipsis(g, s.title, tf, LIST_W - 150), LIST_X + 22 + ROW_H - 18,
                        y + 30, tf, sel ? Color.WHITE : Theme.TEXT);
                Painter.text(g, Painter.ellipsis(g, s.artist, Theme.small(), LIST_W - 150),
                        LIST_X + 22 + ROW_H - 18, y + 50, Theme.small(), Theme.MUTED);
                String cnt = s.items.size() + " 难度";
                Painter.textR(g, cnt, LIST_X + LIST_W - 22, y + 30, Theme.tiny(),
                        sel ? Theme.ACCENT : Theme.DIM);
                String lv = "Lv." + s.hardest().chart.levelLabel();
                Painter.textR(g, lv, LIST_X + LIST_W - 22, y + 50, Theme.tiny(), Theme.levelColor(
                        s.hardest().chart.levelNumber()));
            }
            y += ROW_H;
        }
        g.setClip(null);

        // 滚动指示
        int total = songs.size();
        if (total > VISIBLE) {
            double frac = VISIBLE / (double) total;
            double trackH = LIST_H - 16;
            double barH = Math.max(30, trackH * frac);
            double t = scroll / Math.max(1, total * ROW_H - LIST_H);
            Painter.round(g, LIST_X + LIST_W - 6, LIST_Y + 8 + t * (trackH - barH), 3, barH, 1.5,
                    new Color(120, 160, 220, 90));
        }
    }

    private void ensureDensity(ChartLibrary.Item it) {
        if (it.id.equals(densityId)) return;
        densityId = it.id;
        Chart c = it.chart;
        int bins = 80;
        double dur = Math.max(1, c.durationMs());
        double[] d = new double[bins];
        for (int i = 0; i < c.noteCount(); i++) {
            int b = (int) (c.time[i] / dur * bins);
            if (b >= 0 && b < bins) d[b] += 1;
        }
        double max = 1;
        for (double v : d) max = Math.max(max, v);
        for (int i = 0; i < bins; i++) d[i] /= max;
        density = d;
    }

    private void drawDetail(Graphics2D g, ChartLibrary.Item it) {
        double x = 530;
        double y = LIST_Y;
        double w = Theme.W - 40 - x;
        double h = LIST_H;
        Ui.panel(g, x, y, w, h);

        if (it == null) return;
        BufferedImage cover = app.library.cover(it);
        int cs = 286;
        int cx = (int) (x + 26);
        int cy = (int) (y + 30);
        Painter.round(g, cx + 6, cy + 8, cs, cs, 6, Theme.alpha(Theme.SHADOW, 180));
        Painter.round(g, cx - 6, cy - 6, cs + 12, cs + 12, 8, Theme.BG3);
        g.drawImage(cover, cx, cy, cs, cs, null);
        Painter.strokeRound(g, cx - 6, cy - 6, cs + 12, cs + 12, 8, Theme.LINE, 2f);

        double tx = cx + cs + 34;
        double tw = x + w - 26 - tx;

        Font tf = Theme.f(30, true);
        Painter.textShadow(g, Painter.ellipsis(g, it.chart.displayTitle(), tf, tw), tx,
                y + 62, tf, Color.WHITE, new Color(0, 0, 0, 160));
        Painter.text(g, Painter.ellipsis(g, it.chart.displayArtist(), Theme.body(), tw), tx,
                y + 90, Theme.body(), Theme.MUTED);

        // 难度卡片
        diffRects.clear();
        ChartLibrary.Song s = song();
        double dx = tx;
        double dy = y + 112;
        int perRow = 3;
        double cw = (tw - 16) / perRow;
        for (int i = 0; i < s.items.size(); i++) {
            ChartLibrary.Item di = s.items.get(i);
            double rx = dx + (i % perRow) * (cw + 8);
            double ry = dy + (i / perRow) * 60;
            boolean sel = i == diffIdx;
            Color lc = Theme.levelColor(di.chart.levelNumber());
            Painter.round(g, rx, ry, cw, 52, 9,
                    sel ? Theme.alpha(lc, 60) : new Color(20, 26, 42, 220));
            Painter.strokeRound(g, rx, ry, cw, 52, 9,
                    sel ? Theme.brighten(lc, 0.3f) : new Color(70, 86, 120, 140), sel ? 1.8f : 1f);

            String name = di.chart.difficulty.isBlank() ? "难度 " + (i + 1) : di.chart.difficulty;
            Painter.text(g, Painter.ellipsis(g, name, Theme.f(15, true), cw - 54), rx + 12, ry + 24,
                    Theme.f(15, true), sel ? Color.WHITE : Theme.TEXT);
            Painter.text(g, di.chart.noteCount() + " notes", rx + 12, ry + 42, Theme.tiny(), Theme.DIM);
            Ui.levelBadge(g, rx + cw - 26, ry + 26, di.chart.levelLabel(), di.chart.levelNumber(), false);
            diffRects.add(new double[]{rx, ry, cw, 52});
        }

        double sy = dy + ((s.items.size() + perRow - 1) / perRow) * 60 + 22;
        Painter.fill(g, tx, sy, tw, 1, new Color(90, 118, 160, 70));
        sy += 30;

        String[][] rows = {
                {"BPM", it.chart.timing.bpmLabel()},
                {"时长", it.chart.durationLabel()},
                {"音符", it.chart.noteCount() + "  (长条 " + it.chart.holdCount() + ")"},
                {"谱师", it.chart.creator.isBlank() ? "—" : it.chart.creator},
                {"模式", it.chart.columns + "K"},
        };
        for (String[] row : rows) {
            Painter.text(g, row[0], tx, sy, Theme.small(), Theme.DIM);
            Painter.text(g, row[1], tx + 64, sy, Theme.small(), Theme.TEXT);
            sy += 24;
        }

        // 最佳成绩
        long best = app.settings.bestFor(it.id);
        if (best > 0) {
            Painter.text(g, "最佳", tx, sy + 6, Theme.small(), Theme.DIM);
            Painter.text(g, String.format("%,d", best), tx + 64, sy + 6, Theme.f(17, true), Theme.GOLD);
        } else {
            Painter.text(g, "尚无成绩", tx, sy + 6, Theme.small(), Theme.DIM);
        }

        // 单曲延时 + 练习模式状态
        double off = app.settings.offsetFor(it.id);
        String offText = String.format("本曲延时 %+.0f ms", off);
        Painter.text(g, offText, tx, sy + 34, Theme.small(),
                Math.abs(off) < 0.5 ? Theme.DIM : Theme.ACCENT);
        Painter.text(g, "[ / ] 调整 · , . 调整 10ms", tx + 140, sy + 34, Theme.tiny(), Theme.DIM);

        double by = sy + 52;
        String practice = app.settings.practiceMode ? "练习模式 开" : "练习模式 关";
        Ui.pill(g, tx, by, practice, app.settings.practiceMode ? Theme.OK : Theme.MUTED,
                app.settings.practiceMode);
        double pw = Ui.pillWidthOf(g, practice);
        String speed = String.format("速度 %.1fx", app.settings.scrollSpeed);
        Ui.pill(g, tx + pw + 8, by, speed, Theme.ACCENT, false);
        double sw = Ui.pillWidthOf(g, speed);
        Ui.pill(g, tx + pw + sw + 16, by, "P 键切换练习", Theme.DIM, false);

        // 谱面密度图
        ensureDensity(it);
        if (density.length > 0) {
            double gy = y + h - 118;
            Painter.text(g, "谱面密度", tx, gy - 6, Theme.tiny(), Theme.DIM);
            Painter.textR(g, it.chart.durationLabel(), tx + tw, gy - 6, Theme.tiny(), Theme.DIM);
            double bw = tw / density.length;
            for (int i = 0; i < density.length; i++) {
                double bh = 5 + density[i] * 48;
                Color c = Theme.mix(Theme.laneColor(0), Theme.ACCENT2, (float) density[i]);
                g.setColor(Theme.alpha(c, (int) (60 + 130 * density[i])));
                g.fillRect((int) Math.round(tx + i * bw), (int) Math.round(gy + 50 - bh),
                        Math.max(1, (int) Math.round(bw - 1.5)), (int) Math.round(bh));
            }
            Painter.fill(g, tx, gy + 52, tw, 1, new Color(90, 118, 160, 60));
        }

        // 底部提示
        Painter.text(g, "Enter 开始游玩   ·   Esc 返回", x + 26, y + h - 22, Theme.small(), Theme.DIM);
    }
}
