package komgp.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import komgp.game.GameApp;
import komgp.game.Screen;
import komgp.render.Painter;
import komgp.render.Theme;

/** 主菜单：标题 + 三个入口 + 音符雨背景。 */
public final class TitleScreen extends Screen {

    private static final String[] LABELS = {"开始游戏", "设置", "退出游戏"};
    private final List<Ui.Button> buttons = new ArrayList<>();
    private int selected;

    // 音符雨
    private static final int DROPS = 70;
    private final double[] dx = new double[DROPS];
    private final double[] dy = new double[DROPS];
    private final double[] dsp = new double[DROPS];
    private final int[] dcol = new int[DROPS];
    private final double[] dlen = new double[DROPS];

    public TitleScreen(GameApp app) {
        super(app);
        Random rnd = new Random(99);
        for (int i = 0; i < DROPS; i++) {
            dx[i] = rnd.nextDouble() * Theme.W;
            dy[i] = rnd.nextDouble() * Theme.H;
            dsp[i] = 40 + rnd.nextDouble() * 150;
            dcol[i] = rnd.nextInt(4);
            dlen[i] = 26 + rnd.nextDouble() * 70;
        }
        int w = 260;
        int x = Theme.W / 2 - w / 2;
        for (int i = 0; i < LABELS.length; i++) {
            int idx = i;
            buttons.add(new Ui.Button(x, 372 + i * 66, w, 52, LABELS[i], () -> activate(idx)));
        }
    }

    private void activate(int i) {
        switch (i) {
            case 0 -> {
                app.sfx.play(komgp.audio.Sfx.Kind.OK);
                app.go(new SongSelectScreen(app));
            }
            case 1 -> {
                app.sfx.play(komgp.audio.Sfx.Kind.OK);
                app.go(new SettingsScreen(app, null));
            }
            default -> app.exit();
        }
    }

    @Override
    public void update(double dt, double wallMs) {
        double d = Math.min(50, dt);
        for (int i = 0; i < DROPS; i++) {
            dy[i] += dsp[i] * d / 1000.0;
            if (dy[i] > Theme.H + 80) {
                dy[i] = -80;
                dx[i] = (dx[i] * 1.37 + 31) % Theme.W;
            }
        }
    }

    @Override
    public void keyPressed(int code, double songMs) {
        switch (code) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> {
                selected = (selected + LABELS.length - 1) % LABELS.length;
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                selected = (selected + 1) % LABELS.length;
                app.sfx.play(komgp.audio.Sfx.Kind.MOVE);
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> activate(selected);
            case KeyEvent.VK_ESCAPE -> app.exit();
            case KeyEvent.VK_F11 -> app.setFullscreen(!app.isFullscreen());
            default -> { }
        }
    }

    @Override
    public void mousePressed(double x, double y, int button) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).hit(x, y)) {
                selected = i;
                buttons.get(i).action.run();
                return;
            }
        }
    }

    @Override
    public void render(Graphics2D g) {
        // 背景
        g.setColor(Theme.BG);
        g.fillRect(0, 0, Theme.W, Theme.H);
        // 2D 大色块装饰
        g.setColor(Theme.alpha(Theme.ACCENT, 20));
        g.fillOval(Theme.W - 420, -180, 560, 560);
        g.setColor(Theme.alpha(Theme.ACCENT2, 18));
        g.fillOval(-140, Theme.H - 320, 420, 420);
        g.setColor(Theme.alpha(Theme.LILAC, 14));
        for (int i = -Theme.H; i < Theme.W; i += 128) {
            g.fillPolygon(new int[]{i, i + 28, i + 28 + Theme.H, i + Theme.H},
                    new int[]{0, 0, Theme.H, Theme.H}, 4);
        }

        for (int i = 0; i < DROPS; i++) {
            Color c = Theme.laneColor(dcol[i]);
            double w = 14 + (i % 3) * 4;
            Painter.fill(g, dx[i], dy[i], w, dlen[i], Painter.alpha(c, 46));
            Painter.fill(g, dx[i] + w * 0.3, dy[i] + 3, w * 0.4, dlen[i] - 6,
                    Painter.alpha(Theme.brighten(c, 0.5f), 70));
        }
        Painter.vignette(g, Theme.W, Theme.H, 90);

        // 标题
        double cx = Theme.W / 2.0;
        Font tf = Theme.f(78, true);
        g.setFont(tf);
        Painter.textC(g, "KOMGP", cx + 4, 242, tf, Theme.alpha(Theme.SHADOW, 210));
        Painter.textC(g, "KOMGP", cx, 238, tf, Theme.TEXT);
        Painter.fill(g, cx - 150, 258, 300, 4, Theme.ACCENT);
        Painter.textC(g, "KitsuneOS Music Game Player", cx, 292, Theme.f(17, false), Theme.ACCENT2);
        Painter.textC(g, "支持 Malody .mc/.mcz 与 KOMGP 明文谱面", cx, 320, Theme.small(), Theme.MUTED);

        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).draw(g, i == selected, mouseX, mouseY);
        }

        int songs = app.library.songs.size();
        int charts = app.library.items.size();
        Painter.textC(g, "曲库: " + songs + " 首歌 / " + charts + " 张谱面", cx, 596,
                Theme.small(), Theme.MUTED);

        String hint = "↑ ↓ 选择   Enter 确认   F11 全屏   Esc 退出";
        Painter.textC(g, hint, cx, 682, Theme.tiny(), Theme.MUTED);

        if (app.library.items.isEmpty()) {
            Painter.textC(g, "曲库为空：把 .mc / .mcz 放进 charts/ 目录，或运行 ./run.sh --gen-demo",
                    cx, 640, Theme.tiny(), Theme.WARN);
        }
    }
}
