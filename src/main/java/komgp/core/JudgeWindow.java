package komgp.core;

/**
 * 判定窗口。四个边界把时间轴切成 5 档：
 * {@code [0,w0]=P+, (w0,w1]=P, (w1,w2]=E/L, (w2,w3]=BAD, 之外=MISS}。
 *
 * <p>练习模式通过 {@code scale} 统一放大窗口（更宽松）。
 */
public final class JudgeWindow {

    /** 默认窗口（毫秒）。 */
    public static final double DEFAULT_PP = 20;
    public static final double DEFAULT_P = 45;
    public static final double DEFAULT_EL = 80;
    public static final double DEFAULT_BAD = 120;

    public final double pp;
    public final double p;
    public final double el;
    public final double bad;
    /** 相对默认值的缩放（1.0 = 标准，1.5 = 练习）。 */
    public final double scale;

    public JudgeWindow(double scale) {
        this.scale = Math.max(0.25, scale);
        this.pp = DEFAULT_PP * this.scale;
        this.p = DEFAULT_P * this.scale;
        this.el = DEFAULT_EL * this.scale;
        this.bad = DEFAULT_BAD * this.scale;
    }

    public static JudgeWindow standard() { return new JudgeWindow(1.0); }

    /** 早于/晚于该绝对偏差的按下不再吸附到音符上。 */
    public double missWindow() { return bad; }

    /**
     * 把「命中时刻 - 音符时刻」换算成判定。
     * 负值 = 按早了（Early），正值 = 按晚了（Late）。
     */
    public Judge classify(double dt) {
        double a = Math.abs(dt);
        if (a > bad) return Judge.MISS;
        if (a <= pp) return Judge.PERFECT_PLUS;
        if (a <= p) return Judge.PERFECT;
        if (a <= el) return dt < 0 ? Judge.EARLY : Judge.LATE;
        return Judge.BAD;
    }

    @Override
    public String toString() {
        return String.format("P+%.0f P%.0f E/L%.0f Bad%.0f", pp, p, el, bad);
    }
}
