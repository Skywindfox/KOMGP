package komgp.game;

import java.awt.Graphics2D;

/** 一个界面（主菜单 / 选曲 / 设置 / 游玩 / 结算）。 */
public abstract class Screen {

    protected final GameApp app;
    /** 当前鼠标位置（逻辑坐标，-1 表示不在窗口内）。 */
    protected double mouseX = -1;
    protected double mouseY = -1;

    protected Screen(GameApp app) {
        this.app = app;
    }

    /** 进入该界面时调用一次。 */
    public void onEnter() { }

    /** 离开该界面时调用一次。 */
    public void onExit() { }

    /**
     * @param dtMs   距上一帧的毫秒数（已夹住上限，避免暂停后跳变）
     * @param wallMs 程序启动至今的毫秒数
     */
    public void update(double dtMs, double wallMs) { }

    public abstract void render(Graphics2D g);

    public void keyPressed(int code, double songMs) { }

    public void keyReleased(int code, double songMs) { }

    /** 逻辑坐标（1280x720 空间）。 */
    public void mousePressed(double x, double y, int button) { }

    public void mouseMoved(double x, double y) { }

    /** 由游戏主循环每帧写入，供界面做悬停效果。 */
    public final void setMouse(double x, double y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    public void wheel(int notches) { }

    public void onResize() { }
}
