package komgp.dev;

import java.awt.event.KeyEvent;

import komgp.game.GameApp;
import komgp.util.Log;

/**
 * 冒烟测试：真实窗口 + 真实主循环，脚本化走一遍「标题 -> 选曲 -> 游玩 -> 暂停」，
 * 每步把当前帧写成 PNG 存证，然后退出。
 *
 * <p>不用 java.awt.Robot：新版 JDK 的截屏/注入走屏幕共享 portal，
 * 没有桌面会话时会永久阻塞（本机实测会挂死），因此改为直接抓内部帧缓冲 + 注入输入事件。
 */
public final class Smoke {

    private Smoke() {}

    public static void main(String[] args) throws Exception {
        komgp.game.GameApp app = new komgp.game.GameApp(java.nio.file.Path.of("."));
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
        Thread t = new Thread(() -> {
            try {
                step(app, 2200, "shots/window-title.png");
                key(app, KeyEvent.VK_ENTER);
                step(app, 900, "shots/window-select.png");
                key(app, KeyEvent.VK_RIGHT);
                step(app, 300, null);
                key(app, KeyEvent.VK_ENTER);
                step(app, 1700, "shots/window-loading.png");
                step(app, 4600, "shots/window-play.png");
                step(app, 2600, "shots/window-play2.png");
                key(app, KeyEvent.VK_ESCAPE);
                step(app, 700, "shots/window-pause.png");
                key(app, KeyEvent.VK_ESCAPE);
                step(app, 500, null);
                key(app, KeyEvent.VK_ESCAPE);
                step(app, 500, null);
                // 走一次结算流程
                if (app.screen() instanceof komgp.ui.PlayScreen ps) {
                    ps.debugFinish();
                    step(app, 1600, "shots/window-result.png");
                }
                Log.info("冒烟测试完成");
            } catch (Throwable e) {
                Log.error("冒烟测试异常", e);
            } finally {
                System.exit(0);
            }
        }, "smoke");
        t.setDaemon(true);
        t.start();
    }

    private static void key(GameApp app, int code) {
        app.input.injectKey(code, true);
        app.input.injectKey(code, false);
    }

    private static void step(GameApp app, long ms, String shot) throws InterruptedException {
        Thread.sleep(ms);
        if (shot != null) {
            app.requestCapture(shot);
            // 等一帧把请求消费掉，避免下一个注入的按键和本帧抢在同一次迭代里
            Thread.sleep(120);
        }
    }
}
