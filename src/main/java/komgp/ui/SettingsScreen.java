package komgp.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import komgp.cfg.Settings;
import komgp.core.JudgeWindow;
import komgp.game.GameApp;
import komgp.game.Screen;
import komgp.render.Painter;
import komgp.render.Theme;

/** 设置页：偏移、速度、判定宽松、音量、键位。 */
public final class SettingsScreen extends Screen {

    private enum Kind { NUM, TOGGLE, ACTION }

    private record Row(String label, Kind kind, double min, double max, double step,
                       DoubleSupplier get, DoubleConsumer set, Runnable action,
                       Function<Double, String> fmt) {}

    private final Screen back;
    private final List<Row> rows = new ArrayList<>();
    private int col;
    private int row;
    private int listening = -1;
    private final List<Ui.Button> buttons = new ArrayList<>();
    private final double[] keyRectsX = new double[8];
    private double keyY;

    public SettingsScreen(GameApp app, Screen back) {
        super(app);
        this.back = back;
        Settings s = app.settings;

        add(new Row("全局音频偏移", Kind.NUM, -300, 300, 1,
                () -> s.audioOffsetMs, v -> s.audioOffsetMs = v, null,
                v -> String.format("%+.0f ms", v)));
        add(new Row("判定窗口缩放", Kind.NUM, 0.5, 3.0, 0.1,
                () -> s.judgeScale, v -> s.judgeScale = v, null,
                v -> String.format("%.1fx", v)));
        add(new Row("下落速度", Kind.NUM, 0.5, 8.0, 0.1,
                () -> s.scrollSpeed, v -> s.scrollSpeed = v, null,
                v -> String.format("%.1fx", v)));
        add(new Row("播放速率", Kind.NUM, 0.5, 1.5, 0.05,
                () -> s.playRate, v -> s.playRate = v, null,
                v -> String.format("%.2fx", v)));
        add(new Row("音乐音量", Kind.NUM, 0, 1, 0.05,
                () -> s.volume, v -> s.volume = v, null,
                v -> String.format("%.0f%%", v * 100)));
        add(new Row("打击音音量", Kind.NUM, 0, 1, 0.05,
                () -> s.sfxVolume, v -> s.sfxVolume = v, null,
                v -> String.format("%.0f%%", v * 100)));
        add(new Row("背景压暗", Kind.NUM, 0, 0.95, 0.05,
                () -> s.bgDim, v -> s.bgDim = v, null,
                v -> String.format("%.0f%%", v * 100)));
        add(new Row("练习模式（判定宽松、不死）", Kind.TOGGLE, 0, 1, 1,
                () -> s.practiceMode ? 1 : 0, v -> s.practiceMode = v > 0.5, null,
                v -> v > 0.5 ? "开" : "关"));

        add(new Row("自动播放（AUTO）", Kind.TOGGLE, 0, 1, 1,
                () -> s.autoplay ? 1 : 0, v -> s.autoplay = v > 0.5, null,
                v -> v > 0.5 ? "开" : "关"));
        add(new Row("打击特效", Kind.TOGGLE, 0, 1, 1,
                () -> s.noteEffects ? 1 : 0, v -> s.noteEffects = v > 0.5, null,
                v -> v > 0.5 ? "开" : "关"));
        add(new Row("轨道按键光效", Kind.TOGGLE, 0, 1, 1,
                () -> s.showLaneGlow ? 1 : 0, v -> s.showLaneGlow = v > 0.5, null,
                v -> v > 0.5 ? "开" : "关"));
        add(new Row("显示帧率 / 耗时", Kind.TOGGLE, 0, 1, 1,
                () -> s.showFps ? 1 : 0, v -> s.showFps = v > 0.5, null,
                v -> v > 0.5 ? "开" : "关"));
        add(new Row("目标帧率", Kind.NUM, 30, 240, 10,
                () -> s.targetFps, v -> s.targetFps = (int) Math.round(v), null,
                v -> v <= 0 ? "跟随刷新率" : String.format("%.0f fps", v)));
        add(new Row("全屏（F11）", Kind.TOGGLE, 0, 1, 1,
                () -> s.fullscreen ? 1 : 0, v -> { }, () -> app.setFullscreen(!app.isFullscreen()),
                v -> app.isFullscreen() ? "开" : "关"));
        add(new Row("恢复默认设置", Kind.ACTION, 0, 0, 0, () -> 0, v -> { }, () -> {
            Settings def = new Settings();
            app.settings.audioOffsetMs = def.audioOffsetMs;
            app.settings.visualOffsetMs = def.visualOffsetMs;
            app.settings.volume = def.volume;
            app.settings.sfxVolume = def.sfxVolume;
            app.settings.judgeScale = def.judgeScale;
            app.settings.practiceMode = def.practiceMode;
            app.settings.autoplay = def.autoplay;
            app.settings.scrollSpeed = def.scrollSpeed;
            app.settings.playRate = def.playRate;
            app.settings.bgDim = def.bgDim;
            app.settings.noteEffects = def.noteEffects;
            app.settings.showFps = def.showFps;
            app.settings.showLaneGlow = def.showLaneGlow;
            app.settings.targetFps = def.targetFps;
            app.settings.keyLayouts.clear();
            app.settings.keyLayouts.putAll(def.keyLayouts);
            app.audio.setVolume((float) app.settings.volume);
            app.sfx.setVolume((float) app.settings.sfxVolume);
            app.toast("已恢复默认设置");
        }, v -> ""));
        add(new Row("返回", Kind.ACTION, 0, 0, 0, () -> 0, v -> { }, this::leave,
                v -> ""));

        buttons.add(new Ui.Button(1128, 648, 112, 44, "返回", this::leave));
    }

    private void add(Row r) { rows.add(r); }

    private static final int PER_COL = 8;

    private Row current() {
        int idx = col * PER_COL + row;
        if (idx < 0 || idx >= rows.size()) return null;
        return rows.get(idx);
    }

    private void leave() {
        app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
        app.saveSettings();
        app.go(back != null ? back : new TitleScreen(app));
    }

    private void adjust(int dir) {
        Row r = current();
        if (r == null) return;
        if (r.kind == Kind.TOGGLE) {
            r.set.accept(r.get.getAsDouble() > 0.5 ? 0 : 1);
            afterChange(r, true);
            return;
        }
        if (r.kind == Kind.NUM) {
            double v = r.get.getAsDouble() + dir * r.step;
            v = Math.max(r.min, Math.min(r.max, Math.round(v * 1000) / 1000.0));
            r.set.accept(v);
            afterChange(r, true);
        }
    }

    private void afterChange(Row r, boolean sound) {
        Settings s = app.settings;
        app.audio.setVolume((float) s.volume);
        app.sfx.setVolume((float) s.sfxVolume);
        if (sound) app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
        app.settings.save();
    }

    @Override
    public void keyPressed(int code, double songMs) {
        if (listening >= 0) {
            if (code == KeyEvent.VK_ESCAPE) {
                listening = -1;
                return;
            }
            int cols = app.settings.keyEditColumns;
            app.settings.setKey(cols, listening, code);
            listening = -1;
            app.settings.save();
            app.toast("已绑定 " + Ui.keyName(code));
            app.sfx.play(komgp.audio.Sfx.Kind.OK);
            return;
        }
        switch (code) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> {
                row = Math.floorMod(row - 1, PER_COL);
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                row = Math.floorMod(row + 1, PER_COL);
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> adjust(-1);
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> adjust(1);
            case KeyEvent.VK_TAB -> col = 1 - col;
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                Row r = current();
                if (r == null) return;
                if (r.kind == Kind.TOGGLE) adjust(1);
                else if (r.kind == Kind.ACTION && r.action != null) r.action.run();
            }
            case KeyEvent.VK_ESCAPE -> leave();
            case KeyEvent.VK_F11 -> app.setFullscreen(!app.isFullscreen());
            case KeyEvent.VK_F1 -> app.settings.keyEditColumns =
                    app.settings.keyEditColumns >= 7 ? 4 : app.settings.keyEditColumns + 1;
            default -> { }
        }
    }

    @Override
    public void mousePressed(double x, double y, int button) {
        for (Ui.Button b : buttons) {
            if (b.hit(x, y)) { b.action.run(); return; }
        }
        for (int c = 0; c < 2; c++) {
            for (int rr = 0; rr < PER_COL; rr++) {
                int idx = c * PER_COL + rr;
                if (idx >= rows.size()) continue;
                double bx = c == 0 ? 52 : 656;
                double by = 118 + rr * 52;
                if (x >= bx && x <= bx + 566 && y >= by && y <= by + 46) {
                    col = c;
                    row = rr;
                    Row r = rows.get(idx);
                    if (r.kind == Kind.ACTION) {
                        if (r.action != null) r.action.run();
                    } else if (r.kind == Kind.TOGGLE) {
                        adjust(1);
                    } else {
                        adjust(x > bx + 400 ? 1 : -1);
                    }
                    return;
                }
            }
        }
        // 键位列数切换 + 键位槽
        int cols = app.settings.keyEditColumns;
        for (int k = 4; k <= 7; k++) {
            double bx = 500 + (k - 4) * 74;
            if (x >= bx && x <= bx + 68 && y >= 556 && y <= 592) {
                app.settings.keyEditColumns = k;
                listening = -1;
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
                return;
            }
        }
        for (int i = 0; i < cols; i++) {
            double bx = keyRectsX[i];
            if (bx > 0 && x >= bx && x <= bx + 62 && y >= keyY - 26 && y <= keyY + 26) {
                listening = i;
                return;
            }
        }
    }

    @Override
    public void render(Graphics2D g) {
        g.setColor(Theme.BG);
        g.fillRect(0, 0, Theme.W, Theme.H);
        // 背景网格
        g.setColor(new Color(90, 130, 190, 16));
        for (int x = 0; x < Theme.W; x += 48) g.drawLine(x, 0, x, Theme.H);
        for (int y = 0; y < Theme.H; y += 48) g.drawLine(0, y, Theme.W, y);
        Painter.vignette(g, Theme.W, Theme.H, 60);

        Painter.text(g, "设置", 52, 74, Theme.f(30, true), Theme.TEXT);
        Painter.text(g, "↑↓ 选择   ←→ 调整   Tab 切列   Enter 确认   Esc 保存返回",
                140, 72, Theme.tiny(), Theme.DIM);
        Painter.fill(g, 52, 92, Theme.W - 104, 1, new Color(120, 160, 220, 60));

        for (int c = 0; c < 2; c++) {
            for (int rr = 0; rr < PER_COL; rr++) {
                int idx = c * PER_COL + rr;
                if (idx >= rows.size()) continue;
                drawRow(g, idx, rows.get(idx), c, rr);
            }
        }

        drawKeySection(g);
        for (Ui.Button b : buttons) b.draw(g, false, mouseX, mouseY);
    }

    private void drawRow(Graphics2D g, int idx, Row r, int c, int rr) {
        double x = c == 0 ? 52 : 656;
        double y = 118 + rr * 52;
        boolean sel = c == col && rr == row;
        boolean hover = mouseX >= x && mouseX <= x + 566 && mouseY >= y && mouseY <= y + 46;

        Painter.round(g, x, y, 566, 46, 9,
                sel ? new Color(30, 42, 66, 235) : (hover ? new Color(24, 32, 50, 225)
                        : new Color(16, 21, 34, 210)));
        if (sel) Painter.round(g, x, y, 3, 46, 1.5, Theme.ACCENT);

        Painter.text(g, r.label, x + 18, y + 29, Theme.f(16, sel),
                sel ? Color.WHITE : Theme.TEXT);

        String val = r.kind == Kind.ACTION ? "" : r.fmt.apply(r.get.getAsDouble());
        if (!val.isEmpty()) {
            boolean on = r.get.getAsDouble() > 0.5;
            Color vc = r.kind == Kind.TOGGLE ? (on ? Theme.OK : Theme.DIM) : Theme.ACCENT;
            if (r.kind == Kind.TOGGLE) {
                double sw = 46;
                double sx = x + 566 - 26 - sw;
                Painter.round(g, sx, y + 12, sw, 22, 11, on ? Theme.alpha(Theme.OK, 210)
                        : new Color(60, 70, 92, 220));
                Painter.round(g, sx + (on ? sw - 20 : 2), y + 14, 18, 18, 9, Color.WHITE);
            } else {
                Painter.textR(g, val, x + 566 - 26, y + 29, Theme.f(16, true), vc);
            }
        }

        if (r.kind == Kind.NUM) {
            double ratio = (r.get.getAsDouble() - r.min) / Math.max(1e-9, r.max - r.min);
            Painter.round(g, x + 18, y + 37, 300, 3, 1.5, new Color(50, 62, 88, 220));
            Painter.round(g, x + 18, y + 37, Math.max(2, 300 * ratio), 3, 1.5,
                    sel ? Theme.ACCENT : Theme.alpha(Theme.ACCENT, 150));
        }
    }

    private void drawKeySection(Graphics2D g) {
        Painter.fill(g, 52, 540, Theme.W - 104, 1, new Color(120, 160, 220, 50));
        Painter.text(g, "键位", 52, 580, Theme.f(18, true), Theme.TEXT);

        JudgeWindow w = new JudgeWindow(app.settings.judgeScale);
        Painter.text(g, "当前判定窗口", 52, 614, Theme.tiny(), Theme.DIM);
        Painter.text(g, String.format("P+ ±%.0fms", w.pp), 52, 638, Theme.small(), Theme.judgeColor(komgp.core.Judge.PERFECT_PLUS));
        Painter.text(g, String.format("P ±%.0fms", w.p), 152, 638, Theme.small(), Theme.judgeColor(komgp.core.Judge.PERFECT));
        Painter.text(g, String.format("E/L ±%.0fms", w.el), 240, 638, Theme.small(), Theme.judgeColor(komgp.core.Judge.EARLY));
        Painter.text(g, String.format("BAD ±%.0fms", w.bad), 348, 638, Theme.small(), Theme.judgeColor(komgp.core.Judge.BAD));

        int cols = app.settings.keyEditColumns;
        for (int k = 4; k <= 7; k++) {
            double bx = 500 + (k - 4) * 74;
            boolean on = k == cols;
            Painter.round(g, bx, 556, 68, 34, 8,
                    on ? Theme.alpha(Theme.ACCENT, 90) : new Color(20, 26, 42, 220));
            Painter.strokeRound(g, bx, 556, 68, 34, 8,
                    on ? Theme.ACCENT : new Color(70, 86, 120, 130), 1f);
            Painter.textC(g, k + "K", bx + 34, 578, Theme.f(15, true),
                    on ? Color.WHITE : Theme.MUTED);
        }
        Painter.text(g, listenHint(), 500, 614, Theme.tiny(),
                listening >= 0 ? Theme.WARN : Theme.DIM);

        int[] keys = app.settings.keys(cols);
        keyY = 655;
        for (int i = 0; i < keys.length; i++) {
            double bx = 500 + i * 72;
            keyRectsX[i] = bx - 5;
            boolean listeningThis = listening == i;
            Ui.keyCap(g, bx + 27, keyY, listeningThis ? "…" : Ui.keyName(keys[i]),
                    Theme.laneColor(i), listeningThis, 58);
        }
        Painter.text(g, "点击键帽后按下任意键重新绑定   F1 切换列数   Esc 保存返回", 500, 696,
                Theme.tiny(), Theme.DIM);
    }

    private String listenHint() {
        if (listening >= 0) return "按下要绑定的按键…（Esc 取消）";
        return "";
    }
}
