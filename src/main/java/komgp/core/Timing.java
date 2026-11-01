package komgp.core;

import java.util.Arrays;

/**
 * BPM 时间轴：把 Malody 的 {@code beat = [小节, 分子, 分母]} 换算成毫秒。
 *
 * <p>Malody 的 beat 语义是「小节 + 小节内分数位置」，在 4/4 拍下
 * 绝对拍数 = (bar + num/den) * 4，再按 BPM 分段积分得到时间。
 *
 * <p>所有换算在加载期一次算完，运行期只做二分查找，避免每帧重复积分。
 */
public final class Timing {

    /** 拍点（四分音符计数），升序。 */
    private final double[] beat;
    /** 每个拍点对应的毫秒。 */
    private final double[] ms;
    /** 每个拍点之后生效的 BPM。 */
    private final double[] bpm;
    /** 小节线位置（毫秒），升序，用于下落区节拍线渲染。 */
    private final double[] barMs;

    private final double bpmMain;
    private final double bpmMin;
    private final double bpmMax;

    private Timing(double[] beat, double[] ms, double[] bpm, double maxBeat) {
        this.beat = beat;
        this.ms = ms;
        this.bpm = bpm;

        double mn = Double.MAX_VALUE;
        double mx = -Double.MAX_VALUE;
        for (double b : bpm) {
            mn = Math.min(mn, b);
            mx = Math.max(mx, b);
        }
        this.bpmMin = mn;
        this.bpmMax = mx;
        this.bpmMain = bpm.length > 0 ? bpm[0] : 120;

        // 小节线：4/4 拍，每 4 拍一条
        double firstBar = Math.floor(beat[0] / 4.0) * 4.0;
        double lastBeat = Math.max(firstBar, Math.min(maxBeat, beat[beat.length - 1] + 4 * 4096));
        int count = (int) Math.min(8192, (lastBeat - firstBar) / 4 + 1);
        double[] bars = new double[Math.max(0, count)];
        for (int i = 0; i < bars.length; i++) {
            double b = firstBar + i * 4.0;
            bars[i] = msAt(b);
        }
        this.barMs = bars;
    }

    // ------------------------------------------------------------------ 构造

    /** 输入已按拍点升序排好的 (beat, bpm) 序列；maxBeat 用于确定节拍线范围。 */
    public static Timing of(double[] beats, double[] bpms, double maxBeat) {
        int n = beats.length;
        if (n == 0) {
            return of(new double[]{0}, new double[]{120}, 0);
        }
        double[] ms = new double[n];
        ms[0] = beats[0] * 60000.0 / Math.max(1e-6, bpms[0]);
        for (int i = 1; i < n; i++) {
            double seg = bpms[i - 1] <= 0 ? 120 : bpms[i - 1];
            ms[i] = ms[i - 1] + (beats[i] - beats[i - 1]) * 60000.0 / seg;
        }
        return new Timing(beats, ms, bpms, maxBeat);
    }

    public static Timing ofDefault() {
        return of(new double[]{0}, new double[]{120}, 0);
    }

    // ------------------------------------------------------------------ 查询

    /** 拍点 -> 毫秒。 */
    public double msAt(double beatPos) {
        int n = beat.length;
        if (n == 0) return beatPos * 500;
        if (beatPos <= beat[0]) {
            return ms[0] + (beatPos - beat[0]) * 60000.0 / 120;
        }
        if (beatPos >= beat[n - 1]) {
            double lastBpm = bpm[n - 1] <= 0 ? 120 : bpm[n - 1];
            return ms[n - 1] + (beatPos - beat[n - 1]) * 60000.0 / lastBpm;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (beat[mid] <= beatPos) lo = mid;
            else hi = mid;
        }
        double segBpm = bpm[lo] <= 0 ? 120 : bpm[lo];
        return ms[lo] + (beatPos - beat[lo]) * 60000.0 / segBpm;
    }

    /** 毫秒 -> 拍点（{@link #msAt} 的逆运算，编辑器/导出用）。 */
    public double beatOfMs(double timeMs) {
        int n = beat.length;
        if (n == 0) return timeMs / 500.0;
        if (timeMs <= ms[0]) {
            return beat[0] + (timeMs - ms[0]) * 120.0 / 60000.0;
        }
        if (timeMs >= ms[n - 1]) {
            double lastBpm = bpm[n - 1] <= 0 ? 120 : bpm[n - 1];
            return beat[n - 1] + (timeMs - ms[n - 1]) * lastBpm / 60000.0;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (ms[mid] <= timeMs) lo = mid;
            else hi = mid;
        }
        double segBpm = bpm[lo] <= 0 ? 120 : bpm[lo];
        return beat[lo] + (timeMs - ms[lo]) * segBpm / 60000.0;
    }

    /** 该毫秒时间点生效的 BPM。 */
    public double bpmAtMs(double t) {
        int n = beat.length;
        if (n == 0) return 120;
        if (t <= ms[0]) return bpm[0];
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (ms[mid] <= t) lo = mid;
            else hi = mid;
        }
        while (lo + 1 < n && ms[lo + 1] <= t) lo++;
        return bpm[lo];
    }

    /** 小节线毫秒位置（升序，不可修改）。 */
    public double[] barTimes() { return barMs; }

    public double bpmMain() { return bpmMain; }

    public double bpmMin() { return bpmMin; }

    public double bpmMax() { return bpmMax; }

    public int changeCount() { return beat.length; }

    /** 第 i 个速度点的拍位置（供明文谱面导出使用）。 */
    public double beatAt(int i) { return beat[i]; }

    /** 第 i 个速度点的 BPM。 */
    public double bpmAt(int i) { return bpm[i]; }

    public double firstBeatMs() { return ms.length > 0 ? ms[0] : 0; }

    public double lastBeatMs() { return ms.length > 0 ? ms[ms.length - 1] : 0; }

    /** 供 UI 展示：单值时 "150"，变速时 "120~180"。 */
    public String bpmLabel() {
        if (bpmMax - bpmMin < 0.5) return String.format("%.0f", bpmMain);
        return String.format("%.0f~%.0f", bpmMin, bpmMax);
    }

    @Override
    public String toString() {
        return "Timing[" + beat.length + " 个速度点, " + bpmLabel() + " BPM, "
                + barMs.length + " 小节线]";
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @SuppressWarnings("unused")
    private static double[] sortedCopy(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c;
    }
}
