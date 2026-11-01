package komgp.game;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferStrategy;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

import javax.swing.JFrame;

import komgp.audio.AudioEngine;
import komgp.audio.Sfx;
import komgp.cfg.ChartLibrary;
import komgp.cfg.Settings;
import komgp.render.Painter;
import komgp.render.Theme;
import komgp.ui.TitleScreen;
import komgp.util.Fps;
import komgp.util.Log;

/**
 * 窗口 + 主循环 + 界面栈。
 *
 * <p>渲染走 {@link BufferStrategy} 主动渲染（不依赖 Swing 的重绘机制），
 * 画面按 1280x720 逻辑坐标绘制后整体缩放到窗口，保证任何分辨率下版式一致。
 */
public final class GameApp {

    public final Path baseDir;
    public final Path chartsDir;
    public final Settings settings;
    public final ChartLibrary library;
    public final AudioEngine audio = new AudioEngine();
    public final Sfx sfx = new Sfx();
    public final Fps fps = new Fps();
    public final InputHub input;

    private volatile Screen screen = null;
    private volatile Screen pending = null;
    public volatile long frameCount;

    private JFrame frame;
    private Canvas canvas;
    private BufferStrategy strategy;
    private volatile boolean running;
    private Thread loopThread;
    private java.awt.image.BufferedImage frameBuffer;
    private Graphics2D frameGfx;

    public double scale = 1;
    public double offX;
    public double offY;
    public double mouseX = -1;
    public double mouseY = -1;
    public double wallMs;
    private long startNanos = System.nanoTime();

    private String toast;
    private double toastAt;

    /** 命令行 --edit <file> / --editor 的启动意图（start() 后消费）。 */
    public volatile Path pendingEdit;
    public volatile boolean pendingEditor;

    public GameApp(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath();
        this.chartsDir = this.baseDir.resolve("charts");
        this.settings = Settings.load(this.baseDir.resolve(Settings.FILE));
        this.library = new ChartLibrary(chartsDir);
        this.input = new InputHub(audio);
        audio.setVolume((float) settings.volume);
        sfx.setVolume((float) settings.sfxVolume);
    }

    // ------------------------------------------------------------------ 窗口

    public void start() {
        frame = new JFrame("Malody 4K · Java 下落式音游");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setBackground(Color.BLACK);
        frame.setIgnoreRepaint(true);

        canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        canvas.setIgnoreRepaint(true);
        canvas.setFocusable(true);
        canvas.setFocusTraversalKeysEnabled(false);
        canvas.setPreferredSize(new Dimension(settings.windowWidth, settings.windowHeight));

        frame.getContentPane().add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setMinimumSize(new Dimension(960, 540));

        canvas.addKeyListener(input);
        canvas.addMouseListener(input);
        canvas.addMouseMotionListener(input);
        canvas.addMouseWheelListener(input);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                input.releaseAll();
            }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                onResize();
            }
        });

        frame.setVisible(true);
        canvas.requestFocus();
        canvas.createBufferStrategy(2);
        strategy = canvas.getBufferStrategy();

        if (settings.fullscreen) {
            setFullscreen(true);
        } else {
            // 默认跟随显示器刷新率：帧率高于刷新率只会带来不均匀的呈现
            int hz = displayHz();
            if (settings.targetFps <= 0 || settings.targetFps > hz + 5) {
                settings.targetFps = hz;
            }
        }

        screen = new TitleScreen(this);
        screen.onEnter();
        if (pendingEdit != null || pendingEditor) {
            Path target = pendingEdit;
            pendingEdit = null;
            pendingEditor = false;
            screen.onExit();
            screen = new komgp.ui.EditorScreen(this, target);
            screen.onEnter();
        }

        running = true;
        loopThread = new Thread(this::loop, "render-loop");
        loopThread.setDaemon(false);
        loopThread.setPriority(Thread.MAX_PRIORITY);
        loopThread.start();
    }

    public static int displayHz() {
        try {
            int hz = Math.round(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode().getRefreshRate());
            if (hz < 30 || hz > 480) hz = 60;
            return hz;
        } catch (Exception e) {
            return 60;
        }
    }

    public void exit() {
        running = false;
        try {
            if (loopThread != null) loopThread.join(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        audio.close();
        sfx.close();
        settings.save();
        if (frame != null) frame.dispose();
        System.exit(0);
    }

    public boolean isFullscreen() {
        return settings.fullscreen;
    }

    public void setFullscreen(boolean on) {
        settings.fullscreen = on;
        try {
            GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            if (on) {
                frame.dispose();
                frame.setUndecorated(true);
                frame.setVisible(true);
                dev.setFullScreenWindow(frame);
            } else {
                dev.setFullScreenWindow(null);
                frame.dispose();
                frame.setUndecorated(false);
                frame.setVisible(true);
            }
        } catch (Exception e) {
            Log.warn("切换全屏失败: " + e.getMessage());
            settings.fullscreen = false;
            try {
                frame.dispose();
                frame.setUndecorated(false);
                frame.setVisible(true);
            } catch (Exception ignored) {
                // 忽略
            }
        }
        recreateStrategy();
        canvas.requestFocus();
        settings.save();
    }

    /** 窗口被 dispose/重建后，BufferStrategy 必须重新创建（否则报 "no valid peer"）。 */
    private void recreateStrategy() {
        try {
            canvas.createBufferStrategy(2);
            strategy = canvas.getBufferStrategy();
            frameW = 0;
            frameH = 0;
            if (frameGfx != null) {
                frameGfx.dispose();
                frameGfx = null;
            }
            frameBuffer = null;
        } catch (Exception e) {
            Log.warn("重建缓冲失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 导航

    public Screen screen() { return screen; }

    public void go(Screen next) {
        pending = next;
    }

    public void toast(String msg) {
        toast = msg;
        toastAt = wallMs;
    }

    // ------------------------------------------------------------------ 循环

    private void loop() {
        long last = System.nanoTime();
        long nextFrame = last;
        while (running) {
            long now = System.nanoTime();
            double dt = (now - last) / 1e6;
            last = now;
            if (dt > 100) dt = 100; // 断点/暂停后不要一帧跳太远
            wallMs = (now - startNanos) / 1e6;
            fps.frame(now);

            try {
                if (pending != null) {
                    Screen p = pending;
                    pending = null;
                    if (screen != null) screen.onExit();
                    screen = p;
                    screen.onEnter();
                    input.releaseAll();
                }

                input.drainKeys(this::dispatchKey);
                input.drainMouse(this::dispatchMouse);
                int wheel = input.drainWheel();
                if (wheel != 0 && screen != null) screen.wheel(wheel);
                double hx = input.hoverX();
                double hy = input.hoverY();
                mouseX = hx < 0 ? -1 : (hx - offX) / scale;
                mouseY = hy < 0 ? -1 : (hy - offY) / scale;
                if (screen != null) {
                    screen.setMouse(mouseX, mouseY);
                    screen.mouseMoved(mouseX, mouseY);
                    screen.update(dt, wallMs);
                }

                render();
                frameCount++;
            } catch (Throwable t) {
                Log.error("游戏循环异常", t);
            }

            int target = settings.targetFps <= 0 ? 60 : Math.max(30, Math.min(480, settings.targetFps));
            long frameNanos = 1_000_000_000L / target;
            nextFrame += frameNanos;
            long sleep = nextFrame - System.nanoTime();
            if (sleep > 200_000L) {
                LockSupport.parkNanos(sleep - 100_000L);
                while (System.nanoTime() < nextFrame) Thread.onSpinWait();
            } else if (sleep < -frameNanos * 2) {
                nextFrame = System.nanoTime(); // 落后太多，重新对齐
            }
        }
    }

    private void dispatchKey(InputHub.KeyEv e) {
        if (screen == null) return;
        if (e.down()) screen.keyPressed(e.code(), e.songMs());
        else screen.keyReleased(e.code(), e.songMs());
    }

    private void dispatchMouse(InputHub.MouseEv e) {
        if (screen == null || !e.down()) return;
        screen.mousePressed((e.x() - offX) / scale, (e.y() - offY) / scale, e.button());
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 尺寸变化只做标记，真正的重算在渲染线程里做。
     * （AWT 的 componentResized 在 EDT 上跑，直接改渲染线程正在用的 Graphics 会撞车。）
     */
    private void onResize() {
        if (screen != null) screen.onResize();
    }

    private int frameW;
    private int frameH;

    private void applySize(int w, int h) {
        frameW = w;
        frameH = h;
        double s = Math.min(w / (double) Theme.W, h / (double) Theme.H);
        scale = s;
        offX = (w - Theme.W * s) / 2;
        offY = (h - Theme.H * s) / 2;
        Painter.setDevice(w, h, s, offX, offY);
        rebuildFrameBuffer(w, h);
        Log.debug("画布 " + w + "x" + h + " 缩放 " + String.format("%.2f", s));
    }

    /** 离屏画布：所有绘制先落在 BufferedImage 上，最后一次性贴到窗口表面。 */
    private void rebuildFrameBuffer(int w, int h) {
        if (frameGfx != null) {
            frameGfx.dispose();
            frameGfx = null;
        }
        frameBuffer = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        frameGfx = frameBuffer.createGraphics();
    }

    private void render() {
        if (strategy == null) return;
        if (canvas != null) {
            int cw = canvas.getWidth();
            int ch = canvas.getHeight();
            if (cw > 0 && ch > 0 && (cw != frameW || ch != frameH)) applySize(cw, ch);
        }
        if (frameBuffer == null || frameGfx == null) return;
        int w = frameBuffer.getWidth();
        int h = frameBuffer.getHeight();
        Graphics2D g = frameGfx;
        g.setTransform(new java.awt.geom.AffineTransform());
        g.setClip(null);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        if (screen != null) {
            g.translate(offX, offY);
            g.scale(scale, scale);
            g.setClip(0, 0, Theme.W, Theme.H);
            Painter.quality(g);
            long t0 = System.nanoTime();
            try {
                screen.render(g);
            } catch (Throwable t) {
                Log.error("界面渲染异常: " + screen.getClass().getSimpleName(), t);
            }
            long drawNs = System.nanoTime() - t0;
            drawOverlays(g, drawNs);
            g.setClip(null);
            maybeCapture();
        }

        BufferStrategy bs = strategy;
        do {
            do {
                Graphics2D dg = (Graphics2D) bs.getDrawGraphics();
                try {
                    dg.drawImage(frameBuffer, 0, 0, null);
                } finally {
                    dg.dispose();
                }
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
        // 说明：这里刻意不调用 Toolkit.sync()。它内部走 XSync，在 XWayland /
        // 无合成器等环境下会永久阻塞渲染线程（本机实测直接卡死）。
        // 帧间隔的均匀性改由「按刷新率节拍 + 自旋补时」保证。
    }

    private void drawOverlays(Graphics2D g, long drawNs) {
        if (settings.showFps) {
            Painter.round(g, 12, Theme.H - 92, 152, 66, 8, new Color(0, 0, 0, 160));
            Painter.text(g, String.format("%.0f fps   σ%.1f", fps.fps(), fps.stdMs()), 22,
                    Theme.H - 70, Theme.small(), Theme.OK);
            Painter.text(g, String.format("%.2f ms  p99 %.1f", fps.avgMs(), fps.p99Ms()),
                    22, Theme.H - 52, Theme.tiny(), Theme.MUTED);
            Painter.text(g, String.format("draw %.2f ms", drawNs / 1e6), 22, Theme.H - 34,
                    Theme.tiny(), Theme.DIM);
        }
        if (toast != null) {
            double age = wallMs - toastAt;
            if (age < 2200) {
                double a = age < 1800 ? 1 : (2200 - age) / 400.0;
                int alpha = (int) (Math.max(0, Math.min(1, a)) * 235);
                Painter.round(g, Theme.W / 2.0 - 190, Theme.H - 92, 380, 44, 10,
                        new Color(10, 14, 24, alpha));
                Painter.round(g, Theme.W / 2.0 - 190, Theme.H - 92, 380, 44, 10,
                        new Color(90, 209, 255, alpha / 3));
                Painter.textShadowC(g, toast, Theme.W / 2.0, Theme.H - 63, Theme.body(),
                        new Color(235, 242, 255, alpha), new Color(0, 0, 0, alpha));
            } else {
                toast = null;
            }
        }
    }

    public void saveSettings() {
        settings.save();
    }

    private volatile String capturePath;

    /** 请求把下一帧写入 PNG（开发/冒烟测试用，不依赖 Robot 截屏）。 */
    public void requestCapture(String path) {
        capturePath = path;
    }

    private void maybeCapture() {
        String path = capturePath;
        if (path == null) return;
        capturePath = null;
        try {
            java.io.File f = new java.io.File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(frameBuffer, "png", f);
            Log.info("抓帧: " + path + " " + frameBuffer.getWidth() + "x" + frameBuffer.getHeight());
        } catch (Exception e) {
            Log.warn("抓帧失败: " + e.getMessage());
        }
    }

    /** 窗口句柄（仅 start() 之后可用）。 */
    public Window window() { return frame; }
}
