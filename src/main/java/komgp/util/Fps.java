package komgp.util;

/** 帧率统计：环形缓冲保存最近 N 帧耗时，给出平均/最差/1% 分位。 */
public final class Fps {

    private final long[] frames = new long[180];
    private int index;
    private int count;
    private long last;

    public void frame(long nanos) {
        if (last != 0) {
            frames[index] = nanos - last;
            index = (index + 1) % frames.length;
            if (count < frames.length) count++;
        }
        last = nanos;
    }

    public void reset() {
        index = 0;
        count = 0;
        last = 0;
    }

    public double avgMs() {
        if (count == 0) return 0;
        long sum = 0;
        for (int i = 0; i < count; i++) sum += frames[i];
        return sum / (double) count / 1e6;
    }

    public double fps() {
        double ms = avgMs();
        return ms <= 0 ? 0 : 1000.0 / ms;
    }

    /** 最慢的 1% 帧耗时（抖动的直观指标）。 */
    public double p99Ms() {
        if (count == 0) return 0;
        long[] copy = java.util.Arrays.copyOf(frames, count);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.min(copy.length - 1, Math.floor(copy.length * 0.99));
        return copy[idx] / 1e6;
    }

    /** 帧间隔标准差（ms）。这才是"看起来卡不卡"的指标：均值再低，抖动大就会顿。 */
    public double stdMs() {
        if (count < 2) return 0;
        double avg = avgMs();
        double acc = 0;
        for (int i = 0; i < count; i++) {
            double d = frames[i] / 1e6 - avg;
            acc += d * d;
        }
        return Math.sqrt(acc / count);
    }

    public double maxMs() {
        if (count == 0) return 0;
        long max = 0;
        for (int i = 0; i < count; i++) max = Math.max(max, frames[i]);
        return max / 1e6;
    }
}
