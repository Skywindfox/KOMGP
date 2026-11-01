package komgp.dev;

import komgp.cfg.ChartLibrary;
import komgp.game.GameApp;
import komgp.ui.PlayScreen;
import komgp.util.Log;

/**
 * 真实窗口下的帧率基准：进入游玩界面跑一段时间，输出帧耗时统计后退出。
 * 这是本项目最重要的性能指标（目标：窗口 1600x900 下稳定 ≥60fps）。
 */
public final class WindowBench {

    private WindowBench() {}

    public static void main(String[] args) throws Exception {
        java.nio.file.Path base = java.nio.file.Path.of(".");
        komgp.game.GameApp app = new komgp.game.GameApp(base);
        app.library.scan();
        if (app.library.items.isEmpty()) {
            komgp.tools.DemoGen.generateAll(app.chartsDir);
            app.library.scan();
        }
        app.settings.showFps = true;
        app.settings.autoplay = true;
        install(app);
        app.start();
    }

    public static void install(GameApp app) {
        if ("0".equals(System.getenv("BENCH_VSYNC"))) {
            app.settings.vsync = false;
        }
        if (System.getenv("BENCH_FPS") != null) {
            app.settings.targetFps = Integer.parseInt(System.getenv("BENCH_FPS"));
        }
        String size = System.getenv("BENCH_SIZE");
        if (size != null && size.contains("x")) {
            String[] p = size.split("x");
            app.settings.windowWidth = Integer.parseInt(p[0].trim());
            app.settings.windowHeight = Integer.parseInt(p[1].trim());
        }
        boolean full = "1".equals(System.getenv("BENCH_FULLSCREEN"));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1200);
                if (full) {
                    javax.swing.SwingUtilities.invokeAndWait(() -> app.setFullscreen(true));
                    Thread.sleep(900);
                }
                ChartLibrary.Item item = app.library.findByName("Hard");
                if (item == null && !app.library.items.isEmpty()) item = app.library.items.get(0);
                if (item == null) {
                    Log.error("曲库为空，无法基准测试");
                    System.exit(2);
                    return;
                }
                app.go(new PlayScreen(app, item));
                Thread.sleep(4200); // 载入 + 倒计时
                long f0 = app.frameCount;
                app.fps.reset();
                Thread.sleep(9000);
                long frames = app.frameCount - f0;
                System.out.printf("%n== 窗口基准 ==%n");
                System.out.printf("  窗口 %dx%d, 缩放 %.2fx%n",
                        app.window().getWidth(), app.window().getHeight(), app.scale);
                System.out.printf("  采样 %.1fs, %d 帧, 平均 %.2f ms (%.0f fps), 抖动σ %.2f ms, P99 %.2f ms, 最慢 %.2f ms%n",
                        9.0, frames, app.fps.avgMs(), app.fps.fps(), app.fps.stdMs(),
                        app.fps.p99Ms(), app.fps.maxMs());
            } catch (Throwable e) {
                Log.error("基准测试异常", e);
            } finally {
                System.exit(0);
            }
        }, "window-bench");
        t.setDaemon(true);
        t.start();
    }
}
