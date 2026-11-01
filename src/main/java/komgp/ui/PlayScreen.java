package komgp.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import komgp.audio.Decoder;
import komgp.audio.Pcm;
import komgp.audio.Sfx;
import komgp.cfg.ChartLibrary;
import komgp.core.Chart;
import komgp.core.Judge;
import komgp.core.JudgeWindow;
import komgp.core.PlayState;
import komgp.game.GameApp;
import komgp.game.Screen;
import komgp.format.ChartEntry;
import komgp.format.ChartIO;
import komgp.format.ChartPackage;
import komgp.render.Backdrop;
import komgp.render.NoteGfx;
import komgp.render.Painter;
import komgp.render.Playfield;
import komgp.render.Theme;
import komgp.util.Log;

/** 游玩界面：下落、判定、血条、暂停菜单。 */
public final class PlayScreen extends Screen {

    private static final double LEAD_IN = 2200;

    private enum State { LOADING, PLAYING, PAUSED, DONE, ERROR }

    private final ChartLibrary.Item item;
    private final Chart chart;
    private final Playfield field;

    private volatile boolean ready;
    private volatile String loadError;
    private Pcm pcm;
    private PlayState ps;
    private State state = State.LOADING;

    private BufferedImage bg;
    private BufferedImage scene;
    private BufferedImage jacket;
    private BufferedImage jacketSprite;
    private int jacketKey = -1;
    private static final double JACKET = 148;
    private double chartOffset;
    private double judgeNow;
    private int[] keyToCol = new int[256];
    private int lastMiss;
    private double comboPopAt = -1e9;
    private double doneAt;
    private boolean failed;
    private Thread loader;

    private final List<Ui.Button> pauseButtons = new ArrayList<>();
    private int pauseSel;

    public PlayScreen(GameApp app, ChartLibrary.Item item) {
        super(app);
        this.item = item;
        this.chart = item.chart;
        this.field = new Playfield(chart.columns);
        this.chartOffset = app.settings.offsetFor(item.id);
        java.util.Arrays.fill(keyToCol, -1);
        rebuildKeys();
        app.settings.lastChart = item.id;
    }

    public ChartLibrary.Item item() { return item; }

    @Override
    public void onEnter() {
        state = State.LOADING;
        ready = false;
        loadError = null;
        ensureVisuals();
        startLoading();
    }

    @Override
    public void onExit() {
        app.audio.stop();
    }

    private void rebuildKeys() {
        java.util.Arrays.fill(keyToCol, -1);
        int[] keys = app.settings.keys(chart.columns);
        for (int c = 0; c < keys.length; c++) {
            int vk = keys[c];
            if (vk > 0 && vk < keyToCol.length) keyToCol[vk] = c;
        }
    }

    private void startLoading() {
        ready = false;
        loadError = null;
        state = State.LOADING;
        final int wantIndex = item.indexInPackage;
        final String wantFp = chart.fingerprint;
        Thread t = new Thread(() -> {
            try {
                Pcm p = pcm;
                if (p == null && chart.audioEntry != null) {
                    try {
                        ChartPackage pkg = ChartIO.load(item.path, true);
                        ChartEntry lc = null;
                        for (ChartEntry c : pkg.charts) {
                            if (c.chart.fingerprint.equals(wantFp)) { lc = c; break; }
                        }
                        if (lc == null && wantIndex < pkg.charts.size()) lc = pkg.charts.get(wantIndex);
                        if (lc != null && lc.audio != null) {
                            p = Decoder.decode(lc.audio, lc.audioName);
                        }
                    } catch (RuntimeException e) {
                        Log.warn("音频加载失败，使用静音: " + e.getMessage());
                    }
                }
                pcm = p;
                PlayState st = new PlayState(chart,
                        new JudgeWindow(app.settings.practiceMode ? 1.5 : app.settings.judgeScale),
                        app.settings.practiceMode, app.settings.autoplay);
                ps = st;
                failed = false;
                lastMiss = 0;
                comboPopAt = -1e9;

                app.audio.setRate((float) app.settings.playRate);
                app.audio.setVolume((float) app.settings.volume);
                app.audio.loadSong(p);
                app.audio.play(LEAD_IN);
                state = State.PLAYING;
                ready = true;
            } catch (Throwable e) {
                Log.error("准备游玩失败", e);
                loadError = e.getMessage() == null ? e.toString() : e.getMessage();
                state = State.ERROR;
            }
        }, "chart-loader");
        t.setDaemon(true);
        loader = t;
        t.start();
    }

    private void restart() {
        if (loader != null) loader.interrupt();
        app.audio.stop();
        state = State.LOADING;
        startLoading();
    }

    private double judgeTime(double songMs) {
        return songMs + app.settings.audioOffsetMs + chartOffset;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public void keyPressed(int code, double songMs) {
        if (state == State.ERROR) {
            if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_ENTER) {
                app.go(new SongSelectScreen(app));
            }
            return;
        }
        if (state == State.PAUSED) {
            pauseKey(code);
            return;
        }
        if (state == State.DONE) {
            if (code == KeyEvent.VK_ESCAPE) app.go(new SongSelectScreen(app));
            return;
        }
        if (code == KeyEvent.VK_ESCAPE) {
            pause();
            return;
        }
        if (code == KeyEvent.VK_OPEN_BRACKET) {
            chartOffset -= 1;
            app.settings.setOffsetFor(item.id, chartOffset);
            app.toast(String.format("本曲延时 %+.0f ms", chartOffset));
            return;
        }
        if (code == KeyEvent.VK_CLOSE_BRACKET) {
            chartOffset += 1;
            app.settings.setOffsetFor(item.id, chartOffset);
            app.toast(String.format("本曲延时 %+.0f ms", chartOffset));
            return;
        }
        if (code >= 0 && code < keyToCol.length && keyToCol[code] >= 0) {
            int col = keyToCol[code];
            if (ps != null && state == State.PLAYING) {
                ps.press(col, judgeTime(songMs));
                app.sfx.play(Sfx.Kind.TAP);
            }
        }
    }

    @Override
    public void keyReleased(int code, double songMs) {
        if (state != State.PLAYING || ps == null) return;
        if (code >= 0 && code < keyToCol.length && keyToCol[code] >= 0) {
            ps.release(keyToCol[code], judgeTime(songMs));
        }
    }

    private void pause() {
        if (state != State.PLAYING) return;
        state = State.PAUSED;
        pauseSel = 0;
        app.audio.pause();
        rebuildPauseButtons();
    }

    private void resume() {
        state = State.PLAYING;
        app.audio.resume();
        app.input.releaseAll();
    }

    private void rebuildPauseButtons() {
        pauseButtons.clear();
        double w = 380;
        double h = 46;
        double x = Theme.W / 2.0 - w / 2;
        double y = 236;
        String[] labels = {
                "继续",
                "重新开始",
                String.format("本曲延时 %+.0f ms   ( -1 )", chartOffset),
                String.format("本曲延时 %+.0f ms   ( +1 )", chartOffset),
                "练习模式：" + (app.settings.practiceMode ? "开" : "关") + "（重开后生效）",
                "自动播放：" + (app.settings.autoplay ? "开" : "关") + "（重开后生效）",
                "返回选曲"
        };
        for (int i = 0; i < labels.length; i++) {
            int idx = i;
            pauseButtons.add(new Ui.Button(x, y + i * (h + 8), w, h, labels[i], () -> pauseAction(idx)));
        }
    }

    private void pauseAction(int i) {
        switch (i) {
            case 0 -> resume();
            case 1 -> restart();
            case 2 -> {
                chartOffset -= 1;
                app.settings.setOffsetFor(item.id, chartOffset);
                rebuildPauseButtons();
            }
            case 3 -> {
                chartOffset += 1;
                app.settings.setOffsetFor(item.id, chartOffset);
                rebuildPauseButtons();
            }
            case 4 -> {
                app.settings.practiceMode = !app.settings.practiceMode;
                rebuildPauseButtons();
            }
            case 5 -> {
                app.settings.autoplay = !app.settings.autoplay;
                rebuildPauseButtons();
            }
            default -> app.go(new SongSelectScreen(app));
        }
    }

    private void pauseKey(int code) {
        switch (code) {
            case KeyEvent.VK_ESCAPE -> resume();
            case KeyEvent.VK_UP, KeyEvent.VK_W -> {
                pauseSel = Math.floorMod(pauseSel - 1, pauseButtons.size());
                app.sfx.play(Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                pauseSel = Math.floorMod(pauseSel + 1, pauseButtons.size());
                app.sfx.play(Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> pauseAction(pauseSel);
            case KeyEvent.VK_R -> restart();
            default -> { }
        }
    }

    @Override
    public void mousePressed(double x, double y, int button) {
        if (state != State.PAUSED) return;
        for (int i = 0; i < pauseButtons.size(); i++) {
            if (pauseButtons.get(i).hit(x, y)) {
                pauseSel = i;
                pauseAction(i);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 更新

    @Override
    public void update(double dt, double wallMs) {
        if (state == State.PLAYING && ps != null) {
            double t = judgeTime(app.audio.songMs());
            judgeNow = t;
            ps.update(t);

            int miss = ps.count[Judge.MISS.ordinal()];
            if (miss > lastMiss) {
                app.sfx.play(Sfx.Kind.MISS);
                lastMiss = miss;
            }
            if (ps.combo > 0 && ps.combo != lastCombo) {
                comboPopAt = t;
                lastCombo = ps.combo;
            }

            if (ps.failed) {
                failed = true;
                finish();
            } else if (ps.isFinished()) {
                finish();
            }
        } else if (state == State.DONE) {
            if (wallMs - doneAt > 900) {
                app.go(new ResultScreen(app, item, ps, failed));
            }
        }
    }

    private int lastCombo;

    /** 开发/冒烟测试用：直接走一次正常结算流程。 */
    public void debugFinish() {
        if (ps == null) return;
        state = State.PLAYING;
        judgeNow = ps.chart.lastNoteMs + 2000;
        ps.update(judgeNow);
        finish();
    }

    private void finish() {
        if (state == State.DONE) return;
        state = State.DONE;
        doneAt = app.wallMs;
        ps.finalizePending();
        app.audio.stop();
        long score = ps.score();
        app.settings.recordBest(item.id, score);
        app.settings.save();
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(Graphics2D g) {
        if (state == State.ERROR) {
            renderError(g);
            return;
        }
        ensureVisuals();
        long t0 = komgp.util.Prof.start();
        if (scene != null) Painter.blitExact(g, scene, 0, 0);
        else if (bg != null) Painter.blitExact(g, bg, 0, 0);
        else {
            g.setColor(Theme.BG);
            g.fillRect(0, 0, Theme.W, Theme.H);
        }

        if (ps == null) {
            renderLoading(g);
            return;
        }

        komgp.util.Prof.add("bg", t0);
        NoteGfx.ensure(Painter.scale, chart.columns);
        double pxPerMs = Playfield.PX_PER_MS_BASE * app.settings.scrollSpeed;

        long t1 = komgp.util.Prof.start();
        field.drawDynamic(g, app.settings.showLaneGlow ? ps : null, judgeNow, pxPerMs);
        komgp.util.Prof.add("lanes", t1);
        long t2 = komgp.util.Prof.start();
        field.drawNotes(g, ps, judgeNow, pxPerMs, app.settings.visualOffsetMs,
                app.settings.noteEffects);
        komgp.util.Prof.add("notes", t2);
        long t3 = komgp.util.Prof.start();
        double pulse = Math.max(0, 1 - (judgeNow - ps.lastJudgeAt) / 260.0);
        field.drawJudgeLine(g, pulse);
        field.drawEffects(g, ps, judgeNow, app.settings.noteEffects);
        komgp.util.Prof.add("line+fx", t3);

        long t4 = komgp.util.Prof.start();
        drawHud(g);
        drawJudgePopup(g);
        komgp.util.Prof.add("hud", t4);
        komgp.util.Prof.frame();
        if (judgeNow < 0 && state == State.PLAYING) drawCountdown(g);
        if (state == State.PAUSED) drawPause(g);
        else if (state == State.DONE) drawDone(g);
    }

    /**
     * 仅供截图/基准工具：把外部推进好的状态直接渲染一帧（不触碰音频与计时）。
     */
    public void renderPreview(Graphics2D g, PlayState state, double now) {
        this.ps = state;
        this.judgeNow = now;
        this.state = State.PLAYING;
        ensureVisuals();
        render(g);
    }

    private void ensureVisuals() {
        if (bg == null || !Backdrop.matches(bg)) {
            bg = Backdrop.ofDevice(app.library.backdrop(item), app.settings.bgDim, 2.2);
            scene = null;
        }
        if (scene == null) scene = field.scene(bg);
        if (jacket == null) jacket = app.library.cover(item);
        int key = (int) Math.round(Painter.scale * 1000);
        if (jacketSprite == null || jacketKey != key) {
            jacketKey = key;
            int px = Math.max(8, (int) Math.round(JACKET * Painter.scale));
            jacketSprite = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
            Graphics2D jg = jacketSprite.createGraphics();
            jg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            jg.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            jg.drawImage(jacket, 0, 0, px, px, null);
            jg.dispose();
        }
    }

    private void renderLoading(Graphics2D g) {
        Painter.textC(g, "载入中…", Theme.W / 2.0, Theme.H / 2.0 - 10, Theme.h1(), Theme.TEXT);
        Painter.textC(g, chart.displayTitle() + "  [" + chart.difficulty + "]",
                Theme.W / 2.0, Theme.H / 2.0 + 28, Theme.body(), Theme.MUTED);
        Painter.textC(g, "Esc 返回选曲", Theme.W / 2.0, Theme.H / 2.0 + 60, Theme.tiny(), Theme.DIM);
    }

    private void renderError(Graphics2D g) {
        g.setColor(Theme.BG);
        g.fillRect(0, 0, Theme.W, Theme.H);
        Painter.textC(g, "载入失败", Theme.W / 2.0, Theme.H / 2.0 - 20, Theme.h1(), Theme.DANGER);
        Painter.textC(g, loadError == null ? "未知原因" : loadError, Theme.W / 2.0,
                Theme.H / 2.0 + 16, Theme.body(), Theme.MUTED);
        Painter.textC(g, "按 Esc 返回选曲", Theme.W / 2.0, Theme.H / 2.0 + 56, Theme.body(), Theme.DIM);
    }

    private void drawHud(Graphics2D g) {
        long h0 = komgp.util.Prof.start();
        // 左侧信息
        double lx = 36;
        Painter.text(g, Painter.ellipsis(g, chart.displayTitle(), Theme.f(17, true), 336),
                lx, 56, Theme.f(17, true), Theme.TEXT);
        Painter.text(g, Painter.ellipsis(g, chart.displayArtist(), Theme.small(), 336),
                lx, 78, Theme.small(), Theme.MUTED);

        Ui.levelBadge(g, lx + 20, 116, chart.levelLabel(), chart.levelNumber(), true);
        String diff = chart.difficulty.isBlank() ? "谱面" : chart.difficulty;
        Painter.text(g, Painter.ellipsis(g, diff, Theme.f(16, true), 240), lx + 54, 112,
                Theme.f(16, true), Theme.TEXT);
        Painter.text(g, chart.timing.bpmLabel() + " BPM  ·  " + chart.columns + "K", lx + 54, 132,
                Theme.tiny(), Theme.DIM);

        komgp.util.Prof.add("hud.header", h0);
        long h1 = komgp.util.Prof.start();
        // 判定统计
        Judge[] order = {Judge.PERFECT_PLUS, Judge.PERFECT, Judge.EARLY, Judge.LATE, Judge.BAD, Judge.MISS};
        double cy = 184;
        for (Judge j : order) {
            Painter.text(g, j.label, lx, cy, Theme.f(12, true), Theme.judgeColor(j));
            Painter.textR(g, String.valueOf(ps.count[j.ordinal()]), lx + 300, cy, Theme.f(14, true),
                    Theme.TEXT);
            cy += 22;
        }

        Painter.text(g, "准确率", lx, cy + 26, Theme.tiny(), Theme.DIM);
        Painter.textR(g, String.format("%.2f%%", ps.accuracy() * 100), lx + 300, cy + 26,
                Theme.f(16, true), Theme.ACCENT);
        Painter.text(g, "偏差", lx, cy + 50, Theme.tiny(), Theme.DIM);
        Painter.textR(g, String.format("%+.1f ms", ps.meanOffset()), lx + 300, cy + 50,
                Theme.small(), Math.abs(ps.meanOffset()) > 25 ? Theme.WARN : Theme.MUTED);

        komgp.util.Prof.add("hud.counts", h1);
        long h2 = komgp.util.Prof.start();
        // 曲绘
        if (jacketSprite != null) {
            double jy = 452;
            Painter.round(g, lx + 5, jy + 6, JACKET, JACKET, 6, Theme.alpha(Theme.SHADOW, 180));
            Painter.blitExact(g, jacketSprite, lx, jy);
            Painter.strokeRound(g, lx, jy, JACKET, JACKET, 6, Theme.LINE, 2f);
            Painter.round(g, lx + 8, jy + JACKET - 28, 82, 20, 5, Theme.alpha(Theme.BG, 230));
            Painter.text(g, chart.columns + "K", lx + 16, jy + JACKET - 13, Theme.tiny(), Theme.TEXT);
        }

        komgp.util.Prof.add("hud.jacket", h2);
        long h3 = komgp.util.Prof.start();
        // 血条
        double hpX = field.x0 - 18;
        double hpY = 120;
        double hpH = field.judgeY - hpY - 10;
        double ratio = ps.health / ps.healthMax;
        Color hpColor = ratio > 0.5 ? Theme.OK : (ratio > 0.22 ? Theme.WARN : Theme.DANGER);
        Painter.vbar(g, hpX, hpY, 9, hpH, ratio, new Color(30, 38, 56, 220), hpColor,
                new Color(0, 0, 0, 90));
        Painter.text(g, "HP", hpX - 2, hpY - 10, Theme.f(10, true), Theme.DIM);

        // 右侧分数
        String score = String.format("%,d", ps.score());
        Painter.textR(g, score, Theme.W - 40, 62, Theme.f(34, true), Theme.TEXT);
        Painter.textR(g, String.format("SCORE   %s", ps.grade()), Theme.W - 40, 86,
                Theme.tiny(), Theme.DIM);
        Painter.textR(g, String.format("COMBO %d  ·  MAX %d", ps.combo, ps.maxCombo),
                Theme.W - 40, 110, Theme.tiny(), Theme.MUTED);

        // 右下角状态
        double by = 640;
        String speed = String.format("%.1fx", app.settings.scrollSpeed);
        String rate = String.format("%.2fx", app.settings.playRate);
        Painter.textR(g, "速度 " + speed + "   速率 " + rate, Theme.W - 40, by + 24,
                Theme.tiny(), Theme.DIM);
        double bx = Theme.W - 40;
        if (app.settings.autoplay) {
            bx -= 58;
            Ui.pill(g, bx, by - 2, "AUTO", Theme.WARN, true);
        }
        if (app.settings.practiceMode) {
            bx -= 74;
            Ui.pill(g, bx, by - 2, "练习模式", Theme.OK, true);
        }
        if (Math.abs(chartOffset) >= 0.5) {
            bx -= 96;
            Ui.pill(g, bx, by - 2, String.format("OFFSET %+.0f", chartOffset), Theme.ACCENT, false);
        }

        komgp.util.Prof.add("hud.score", h3);
        long h4 = komgp.util.Prof.start();
        // 底部键位
        int[] keys = app.settings.keys(chart.columns);
        for (int c = 0; c < chart.columns; c++) {
            double cx = field.laneCenter(c);
            boolean down = ps.keyDown.length > c && ps.keyDown[c];
            Ui.keyCap(g, cx, Theme.H - 34, Ui.keyName(keys[c]), Theme.laneColor(c), down, 40);
        }

        komgp.util.Prof.add("hud.keycaps", h4);
        long h5 = komgp.util.Prof.start();
        // 连击
        if (ps.combo >= 3) {
            double age = judgeNow - comboPopAt;
            double pop = age >= 0 && age < 140 ? 1 + 0.22 * (1 - age / 140.0) : 1;
            double cx = Theme.W / 2.0;
            double comboY = 268;
            g.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, 0.92f));
            g.translate(cx, comboY);
            g.scale(pop, pop);
            Painter.textC(g, String.valueOf(ps.combo), 0, 0, Theme.f(64, true),
                    Painter.alpha(Theme.TEXT, 235));
            Painter.textC(g, "COMBO", 0, 26, Theme.f(13, true), Painter.alpha(Theme.ACCENT, 200));
            g.scale(1 / pop, 1 / pop);
            g.translate(-cx, -comboY);
            g.setComposite(java.awt.AlphaComposite.SrcOver);
        }
        komgp.util.Prof.add("hud.combo", h5);
    }

    private void drawJudgePopup(Graphics2D g) {
        double age = judgeNow - ps.lastJudgeAt;
        if (ps.lastJudge == null || age < 0 || age > 460) return;
        double a = age < 320 ? 1 : (460 - age) / 140.0;
        double pop = age < 110 ? 1 + 0.3 * (1 - age / 110.0) : 1;
        Color c = Theme.judgeColor(ps.lastJudge);
        double cx = Theme.W / 2.0;
        double cy = field.judgeY - 132;
        int alpha = (int) (Math.max(0, Math.min(1, a)) * 255);
        g.translate(cx, cy);
        g.scale(pop, pop);
        Painter.textC(g, ps.lastJudge.label, 0, 0, Theme.f(30, true), Painter.alpha(c, alpha));
        if (ps.lastJudge != Judge.MISS && ps.lastJudge != Judge.PERFECT_PLUS) {
            Painter.textC(g, String.format("%+.0f ms", ps.lastOffset), 0, 22,
                    Theme.tiny(), Painter.alpha(Theme.MUTED, alpha));
        }
        g.scale(1 / pop, 1 / pop);
        g.translate(-cx, -cy);
    }

    private void drawCountdown(Graphics2D g) {
        double t = judgeNow;
        int n = (int) Math.ceil(-t / 700.0);
        String text = n <= 0 ? "GO" : String.valueOf(Math.min(3, n));
        double frac = (-t % 700) / 700.0;
        double scale = 1 + frac * 0.35;
        double alpha = 0.35 + 0.65 * frac;
        g.translate(Theme.W / 2.0, Theme.H / 2.0 - 40);
        g.scale(scale, scale);
        Painter.textC(g, text, 0, 0, Theme.f(96, true),
                Painter.alpha(Theme.TEXT, (int) (alpha * 235)));
        g.scale(1 / scale, 1 / scale);
        g.translate(-Theme.W / 2.0, -(Theme.H / 2.0 - 40));

        Painter.textC(g, chart.displayTitle() + "   ·   " + chart.difficulty,
                Theme.W / 2.0, Theme.H / 2.0 + 60, Theme.body(), Painter.alpha(Theme.MUTED, 180));
    }

    private void drawPause(Graphics2D g) {
        g.setColor(new Color(4, 6, 12, 200));
        g.fillRect(0, 0, Theme.W, Theme.H);
        Painter.textC(g, "已暂停", Theme.W / 2.0, 176, Theme.f(38, true), Theme.TEXT);
        Painter.textC(g, "Esc 继续   ↑↓ 选择   Enter 确认", Theme.W / 2.0, 204,
                Theme.small(), Theme.DIM);
        for (int i = 0; i < pauseButtons.size(); i++) {
            pauseButtons.get(i).draw(g, i == pauseSel, mouseX, mouseY);
        }
        Painter.textC(g, "提示：游戏内 [ ] 可直接微调本曲延时", Theme.W / 2.0,
                Theme.H - 40, Theme.tiny(), Theme.DIM);
    }

    private void drawDone(Graphics2D g) {
        double k = Math.min(1, (app.wallMs - doneAt) / 300.0);
        g.setColor(new Color(4, 6, 12, (int) (190 * k)));
        g.fillRect(0, 0, Theme.W, Theme.H);
        String text = failed ? "FAILED" : "COMPLETE";
        Painter.textC(g, text, Theme.W / 2.0, Theme.H / 2.0,
                Theme.f(56, true), failed ? Theme.DANGER : Theme.OK);
    }
}
