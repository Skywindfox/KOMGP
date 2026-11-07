package komgp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一张已经解析好、可以直接游玩的谱面。
 *
 * <p>音符数据用并列数组（结构数组 -> 数组结构）存储：
 * 加载期一次排好序，运行期按下标顺序遍历，避免每帧创建/遍历对象，减少 GC 压力。
 */
public final class Chart {

    // ---- 元信息
    public final String title;
    public final String titleOrig;
    public final String artist;
    public final String artistOrig;
    public final String creator;
    /** 难度名，例如 "Hard" / "Lv.12"。 */
    public final String difficulty;
    /** 难度等级标签（可空），例如 "12"。 */
    public final String level;

    // ---- 来源
    /** 谱面文件路径（.mc / .mcz 内某一张）。 */
    public final String sourcePath;
    /** 若谱面来自 .mcz 内嵌的 .mc，这里是内层容器名；顶层文件为空串。 */
    public final String container;
    /** 音频/曲绘在压缩包内的条目名，null 表示外部文件。 */
    public final String audioEntry;
    public final String coverEntry;
    public final String backgroundEntry;

    // ---- 模式
    public final int columns;
    public final int rawMode;
    public final int rawColumns;
    /** 谱面自带的下落速度提示（meta.mode_ext.speed），0 表示未设置。 */
    public final int scrollHint;
    /** 加载过程中发生的降级/纠正说明，给 UI 与日志用。 */
    public final String warning;

    // ---- 音符（按时间升序，并列数组）
    public final int[] column;
    public final double[] time;
    public final double[] endTime;
    public final boolean[] hold;

    /**
     * 谱面自带的音频延迟（毫秒）。Malody 的 SoundCue offset 语义是
     * 「音频第 0 帧出现在谱面第 offset 毫秒」，因此这里只延迟音频播放，
     * 绝不改动音符时间轴。
     */
    public final double audioDelayMs;
    public final Timing timing;
    public final double lastNoteMs;
    public final String fingerprint;

    private Chart(Builder b) {
        this.title = b.title;
        this.titleOrig = b.titleOrig;
        this.artist = b.artist;
        this.artistOrig = b.artistOrig;
        this.creator = b.creator;
        this.difficulty = b.difficulty;
        this.level = b.level;
        this.sourcePath = b.sourcePath;
        this.container = b.container;
        this.audioEntry = b.audioEntry;
        this.coverEntry = b.coverEntry;
        this.backgroundEntry = b.backgroundEntry;
        this.columns = b.columns;
        this.rawMode = b.rawMode;
        this.rawColumns = b.rawColumns;
        this.scrollHint = b.scrollHint;
        this.warning = b.warning;
        this.column = b.cols;
        this.time = b.times;
        this.endTime = b.ends;
        this.hold = b.holds;
        this.audioDelayMs = Math.max(0, b.audioDelayMs);
        this.timing = b.timing;
        double last = 0;
        for (double t : time) last = Math.max(last, t);
        for (double t : endTime) last = Math.max(last, t);
        this.lastNoteMs = last;
        this.fingerprint = fingerprintOf(sourcePath, title, difficulty, time.length);
    }

    public int noteCount() { return time.length; }

    public int holdCount() {
        int n = 0;
        for (boolean h : hold) if (h) n++;
        return n;
    }

    /** 估算曲长：最后一个音符 + 2 秒尾音。 */
    public double durationMs() {
        return Math.max(lastNoteMs + 2000, 10000);
    }

    public String durationLabel() {
        int s = (int) Math.round(durationMs() / 1000.0);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    /** 徽章上显示的等级：优先 meta.level，其次从难度名里抠数字，最后 "?"。 */
    public String levelLabel() {
        if (level != null && !level.isBlank()) return level.trim();
        String digits = difficulty == null ? "" : difficulty.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "?" : digits;
    }

    /** 难度等级数值（用于排序/着色），解析失败给 1。 */
    public int levelNumber() {
        String s = levelLabel().replaceAll("[^0-9]", "");
        if (s.isEmpty()) return 1;
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(s.length() > 2 ? s.substring(0, 2) : s)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public String displayTitle() {
        return titleOrig != null && !titleOrig.isBlank() ? titleOrig : title;
    }

    public String displayArtist() {
        return artistOrig != null && !artistOrig.isBlank() ? artistOrig : artist;
    }

    private static String fingerprintOf(String path, String title, String diff, int notes) {
        String s = (path == null ? "" : path) + '|' + title + '|' + diff + '|' + notes;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    @Override
    public String toString() {
        return title + " [" + difficulty + "] " + noteCount() + " notes";
    }

    // ------------------------------------------------------------------ Builder

    public static Builder builder() { return new Builder(); }

    /** 谱面构建器：负责排序、去重、列号裁剪，保证下游可以直接按索引遍历。 */
    public static final class Builder {
        public String title = "Untitled";
        public String titleOrig;
        public String artist = "Unknown";
        public String artistOrig;
        public String creator = "";
        public String difficulty = "";
        public String level = "";
        public String sourcePath = "";
        public String container = "";
        public String audioEntry;
        public String coverEntry;
        public String backgroundEntry;
        public int columns = 4;
        public int rawMode;
        public int rawColumns;
        public int scrollHint;
        public String warning = "";
        public Timing timing = Timing.ofDefault();
        public double audioDelayMs;

        private int[] cols = new int[0];
        private double[] times = new double[0];
        private double[] ends = new double[0];
        private boolean[] holds = new boolean[0];
        private int size;

        /** 加一个音符；col 越界会被裁剪，时间非法的音符会被丢弃。 */
        public Builder add(int col, double t, double end, boolean isHold) {
            if (Double.isNaN(t) || Double.isInfinite(t)) return this;
            if (col < 0) return this;
            if (size == times.length) grow();
            cols[size] = col;
            times[size] = t;
            ends[size] = isHold ? Math.max(t + 20, end) : t;
            holds[size] = isHold && end > t + 20;
            size++;
            return this;
        }

        private void grow() {
            int cap = Math.max(64, times.length + (times.length >> 1));
            cols = java.util.Arrays.copyOf(cols, cap);
            times = java.util.Arrays.copyOf(times, cap);
            ends = java.util.Arrays.copyOf(ends, cap);
            holds = java.util.Arrays.copyOf(holds, cap);
        }

        public int size() { return size; }

        public Chart build() {
            // 1) 裁剪非法列、按 (时间, 列) 排序
            List<Integer> idx = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                if (cols[i] < columns) idx.add(i);
            }
            idx.sort(Comparator.<Integer>comparingDouble(i -> times[i]).thenComparingInt(i -> cols[i]));

            // 2) 去重：同一列 3ms 内重复的音符只保留一个
            double[] lastT = new double[columns];
            java.util.Arrays.fill(lastT, Double.NEGATIVE_INFINITY);
            int out = 0;
            int[] oc = new int[idx.size()];
            double[] ot = new double[idx.size()];
            double[] oe = new double[idx.size()];
            boolean[] oh = new boolean[idx.size()];
            for (int k = 0; k < idx.size(); k++) {
                int i = idx.get(k);
                int c = cols[i];
                if (times[i] - lastT[c] < 3.0) continue;
                lastT[c] = times[i];
                oc[out] = c;
                ot[out] = times[i];
                oe[out] = ends[i];
                oh[out] = holds[i];
                out++;
            }
            this.cols = java.util.Arrays.copyOf(oc, out);
            this.times = java.util.Arrays.copyOf(ot, out);
            this.ends = java.util.Arrays.copyOf(oe, out);
            this.holds = java.util.Arrays.copyOf(oh, out);
            this.size = out;
            return new Chart(this);
        }
    }
}
