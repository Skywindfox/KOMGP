package komgp.game;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import komgp.audio.AudioEngine;

/**
 * 输入收集器：在 AWT 事件线程里给每个按键打上「当时的歌曲时间」戳，再交给渲染线程处理。
 *
 * <p>为什么要在这里打戳：AWT 事件到渲染线程要经过一次队列，延迟不稳定（1~20ms）。
 * 节奏游戏里这个抖动会直接变成判定误差。读取 {@link AudioEngine#songMs()} 只是几次
 * volatile 读，代价可以忽略，但能把误差压到线程调度级别。
 *
 * <p>队列只被 AWT 线程写、渲染线程读，用 synchronized 保护一个 ArrayDeque 即可。
 */
public final class InputHub implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {

    public record KeyEv(int code, boolean down, double songMs, double wallMs) {}

    public record MouseEv(int button, boolean down, double x, double y, double wallMs) {}

    private final AudioEngine audio;
    private final ArrayDeque<KeyEv> keys = new ArrayDeque<>();
    private final ArrayDeque<MouseEv> mouse = new ArrayDeque<>();
    private final Set<Integer> held = new HashSet<>();

    private volatile double hoverX = -1;
    private volatile double hoverY = -1;
    private volatile int wheel;
    /** 最近一次按键事件的修饰键状态（Ctrl/Shift/Alt）。 */
    private volatile int modifiers;
    private long startNanos = System.nanoTime();

    public InputHub(AudioEngine audio) {
        this.audio = audio;
    }

    private double wall() {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    public void drainKeys(Consumer<KeyEv> sink) {
        while (true) {
            KeyEv e;
            synchronized (keys) {
                e = keys.poll();
            }
            if (e == null) break;
            sink.accept(e);
        }
    }

    public void drainMouse(Consumer<MouseEv> sink) {
        while (true) {
            MouseEv e;
            synchronized (mouse) {
                e = mouse.poll();
            }
            if (e == null) break;
            sink.accept(e);
        }
    }

    /** 供冒烟测试注入按键（走和真实键盘完全相同的处理路径）。 */
    public void injectKey(int code, boolean down) {
        synchronized (keys) {
            if (down) held.add(code);
            else held.remove(code);
            keys.add(new KeyEv(code, down, audio.songMs(), wall()));
        }
    }

    public int drainWheel() {
        int w = wheel;
        wheel = 0;
        return w;
    }

    public boolean isCtrl() {
        return (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
    }

    public boolean isShift() {
        return (modifiers & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0;
    }

    public double hoverX() { return hoverX; }

    public double hoverY() { return hoverY; }

    /** 窗口失焦：把所有按住键当作松开，避免「卡键」。 */
    public void releaseAll() {
        synchronized (keys) {
            for (int code : held) {
                keys.add(new KeyEv(code, false, audio.songMs(), wall()));
            }
            held.clear();
        }
    }

    // ------------------------------------------------------------------ AWT

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();
        modifiers = e.getModifiersEx();
        synchronized (keys) {
            if (!held.add(code)) return; // 系统自动重复，丢弃
            keys.add(new KeyEv(code, true, audio.songMs(), wall()));
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int code = e.getKeyCode();
        modifiers = e.getModifiersEx();
        synchronized (keys) {
            held.remove(code);
            keys.add(new KeyEv(code, false, audio.songMs(), wall()));
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // 不使用字符输入
    }

    @Override
    public void mousePressed(MouseEvent e) {
        synchronized (mouse) {
            mouse.add(new MouseEv(e.getButton(), true, e.getX(), e.getY(), wall()));
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        synchronized (mouse) {
            mouse.add(new MouseEv(e.getButton(), false, e.getX(), e.getY(), wall()));
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        hoverX = e.getX();
        hoverY = e.getY();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        hoverX = e.getX();
        hoverY = e.getY();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        wheel += e.getWheelRotation();
    }

    @Override
    public void mouseClicked(MouseEvent e) { }

    @Override
    public void mouseEntered(MouseEvent e) { }

    @Override
    public void mouseExited(MouseEvent e) {
        hoverX = -1;
        hoverY = -1;
    }
}
