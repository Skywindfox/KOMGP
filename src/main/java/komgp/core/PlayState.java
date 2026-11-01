package komgp.core;

/**
 * 游玩状态机：命中判定、长条、连击、血量、评分、结算数据。
 *
 * <p>线程模型：只在渲染/逻辑线程上被调用，因此内部不加锁；输入事件由
 * {@code InputHub} 带上音频时钟时间戳后投递到这里。
 *
 * <p>性能要点：
 * <ul>
 *   <li>{@code result}/{@code holdIdx} 等全部是预分配的基本类型数组；</li>
 *   <li>miss 扫描用游标 {@code scan} 单调前进，总体 O(n)；</li>
 *   <li>命中特效写进定长环形缓冲，渲染线程直接读，不产生任何临时对象。</li>
 * </ul>
 */
public final class PlayState {

    public static final int PENDING = -1;

    public final Chart chart;
    public final JudgeWindow win;
    /** 练习模式：判定放宽 + 不死。 */
    public final boolean practice;
    public final boolean autoplay;

    private final int n;
    private final int[] result;
    private final boolean[] holding;
    private final int[] holdIdx;

    /** 单调前进：第一个尚未出结果的下标。 */
    private int scan;

    public final int[] count = new int[Judge.values().length];
    public int combo;
    public int maxCombo;
    public int holdBreaks;
    public boolean failed;
    public boolean finished;

    public double health = 100;
    public double healthMax = 100;

    public Judge lastJudge;
    public double lastJudgeAt = -1e9;
    public double lastOffset;

    /** 命中偏差累计（只统计 P+/P/E/L），用于结算时给出延时建议。 */
    public double offsetSum;
    public int offsetCount;

    private double now;

    /** 按键按下状态（渲染用）。 */
    public final boolean[] keyDown;

    // ---- 命中特效环形缓冲（渲染线程只读）
    public static final class Fx {
        public int col;
        public Judge judge = Judge.PERFECT;
        public double at;
        public double offset;
        public boolean valid;
    }

    public static final int FX_SLOTS = 64;
    public final Fx[] fx = new Fx[FX_SLOTS];
    public int fxHead;

    public PlayState(Chart chart, JudgeWindow win, boolean practice, boolean autoplay) {
        this.chart = chart;
        this.win = win;
        this.practice = practice;
        this.autoplay = autoplay;
        this.n = chart.noteCount();
        this.result = new int[n];
        this.holding = new boolean[n];
        this.holdIdx = new int[chart.columns];
        this.keyDown = new boolean[chart.columns];
        java.util.Arrays.fill(result, PENDING);
        java.util.Arrays.fill(holdIdx, -1);
        for (int i = 0; i < FX_SLOTS; i++) fx[i] = new Fx();
    }

    // ------------------------------------------------------------------ 查询

    public int noteCount() { return n; }

    public int judgeOf(int i) { return result[i]; }

    public boolean judged(int i) { return result[i] != PENDING; }

    /** 该长条当前是否正被按住（用于渲染剩余条身）。 */
    public boolean isHolding(int i) {
        int col = chart.column[i];
        return holdIdx[col] == i;
    }

    public int judgedCount() {
        int c = 0;
        for (int r : result) if (r != PENDING) c++;
        return c;
    }

    public boolean allJudged() { return judgedCount() == n; }

    /** 已判定音符的实时准确率（没有判定数据时按 100% 显示）。 */
    public double accuracy() {
        int jc = judgedCount();
        if (jc == 0) return 1;
        return Math.max(0, Math.min(1, offsetWeight() / jc));
    }

    private double offsetWeight() {
        double s = 0;
        for (int i = 0; i < n; i++) {
            int r = result[i];
            if (r != PENDING) s += Judge.values()[r].weight;
        }
        return s;
    }

    /** 结算分数：按整张谱面的权重平均，再映射到 100 万。 */
    public long score() {
        if (n == 0) return 0;
        return Math.round(Math.max(0, Math.min(1, offsetWeight() / n)) * 1_000_000L);
    }

    /** 已判定音符的平均偏差（ms），正数=整体偏晚。 */
    public double meanOffset() {
        return offsetCount == 0 ? 0 : offsetSum / offsetCount;
    }

    public boolean isDead() { return failed; }

    /** 歌曲结束：最后一个音符时刻过去 1 秒且全部判完。 */
    public boolean isFinished() {
        return !failed && now > chart.lastNoteMs + 1000 && allJudged();
    }

    public int firstVisibleFrom(double t) {
        // 二分找第一个 endTime >= t 的音符
        int lo = 0;
        int hi = n - 1;
        int ans = n;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (chart.endTime[mid] >= t) {
                ans = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return ans;
    }

    // ------------------------------------------------------------------ 更新

    public void update(double nowMs) {
        this.now = nowMs;

        if (autoplay) runAutoplay(nowMs);

        // 1) 超过判定窗口仍未击打 -> MISS
        while (scan < n && result[scan] != PENDING) scan++;
        double limit = nowMs - win.missWindow();
        for (int i = scan; i < n; i++) {
            if (chart.time[i] >= limit) break;
            if (result[i] == PENDING) {
                apply(i, Judge.MISS, chart.time[i] - nowMs);
            }
        }
        while (scan < n && result[scan] != PENDING) scan++;

        // 2) 长条尾判：还按着且已经到尾部 -> 正常结束
        for (int c = 0; c < holdIdx.length; c++) {
            int i = holdIdx[c];
            if (i < 0) continue;
            if (!keyDown[c] && !autoplay) {
                // 按键丢了（失焦等）按松开处理
                release(c, nowMs);
                continue;
            }
            if (nowMs >= chart.endTime[i]) {
                holdIdx[c] = -1;
            }
        }

        if (!finished && isFinished()) {
            finished = true;
        }
    }

    private int autoCursor;

    private void runAutoplay(double nowMs) {
        while (autoCursor < n && chart.time[autoCursor] <= nowMs) {
            int i = autoCursor++;
            if (result[i] != PENDING) continue;
            int c = chart.column[i];
            // 以音符时刻精确命中，保证 AUTO 一定是 P+
            press(c, chart.time[i]);
            if (!chart.hold[i]) keyDown[c] = false;
        }
        for (int c = 0; c < holdIdx.length; c++) {
            int i = holdIdx[c];
            if (i >= 0 && chart.endTime[i] <= nowMs) {
                holdIdx[c] = -1;
                keyDown[c] = false;
            }
        }
    }

    // ------------------------------------------------------------------ 输入

    /** 按下；返回本次判定，空按返回 null。 */
    public Judge press(int col, double at) {
        if (col < 0 || col >= chart.columns) return null;
        keyDown[col] = true;
        if (failed) return null;

        double wb = win.missWindow();
        int best = -1;
        double bestAbs = Double.MAX_VALUE;
        for (int i = scan; i < n; i++) {
            double t = chart.time[i];
            if (t > at + wb) break;
            if (result[i] != PENDING || chart.column[i] != col) continue;
            double d = Math.abs(t - at);
            if (d <= wb && d < bestAbs) {
                bestAbs = d;
                best = i;
            }
        }
        if (best < 0) return null;

        double dt = at - chart.time[best];
        Judge j = win.classify(dt);
        apply(best, j, dt);
        if (chart.hold[best] && j != Judge.MISS) {
            holdIdx[col] = best;
            holding[best] = true;
        }
        return j;
    }

    /** 松开；长条提前松手会断连击并扣血。 */
    public void release(int col, double at) {
        if (col < 0 || col >= chart.columns) return;
        keyDown[col] = false;
        int i = holdIdx[col];
        if (i < 0) return;
        holdIdx[col] = -1;
        holding[i] = false;
        double rest = chart.endTime[i] - at;
        if (rest > RELEASE_GRACE_MS) {
            holdBreaks++;
            combo = 0;
            damage(practice ? 1.5 : 4.0);
        }
    }

    /** 允许的提前松手误差。 */
    public static final double RELEASE_GRACE_MS = 110;

    // ------------------------------------------------------------------ 结算

    private void apply(int i, Judge j, double dt) {
        result[i] = j.ordinal();
        count[j.ordinal()]++;

        if (j == Judge.MISS || j == Judge.BAD) {
            combo = 0;
        } else {
            combo++;
            if (combo > maxCombo) maxCombo = combo;
        }

        if (j.tier <= 2) {
            offsetSum += dt;
            offsetCount++;
        }

        switch (j) {
            case PERFECT_PLUS -> heal(0.85);
            case PERFECT -> heal(0.60);
            case EARLY, LATE -> damage(0.30);
            case BAD -> damage(practice ? 1.2 : 2.60);
            case MISS -> damage(practice ? 2.5 : 7.00);
            default -> { }
        }

        lastJudge = j;
        lastOffset = dt;
        lastJudgeAt = now;

        Fx e = fx[fxHead];
        fxHead = (fxHead + 1) % FX_SLOTS;
        e.col = chart.column[i];
        e.judge = j;
        e.at = now;
        e.offset = dt;
        e.valid = true;
    }

    private void heal(double v) {
        health = Math.min(healthMax, health + v);
    }

    private void damage(double v) {
        health -= v;
        if (health <= 0) {
            health = 0;
            if (!practice) failed = true;
        }
    }

    /** 歌曲结束/中途退出时，把没判的音符全部记 MISS，保证结算数据自洽。 */
    public void finalizePending() {
        for (int i = 0; i < n; i++) {
            if (result[i] == PENDING) {
                result[i] = Judge.MISS.ordinal();
                count[Judge.MISS.ordinal()]++;
            }
        }
    }

    /** 供结算页使用：评级字母。 */
    public String grade() {
        double a = accuracy();
        if (a >= 0.98) return "SS";
        if (a >= 0.95) return "S";
        if (a >= 0.90) return "A";
        if (a >= 0.80) return "B";
        if (a >= 0.70) return "C";
        return "D";
    }

    /** 全连（无 BAD/MISS）。 */
    public boolean fullCombo() {
        return count[Judge.MISS.ordinal()] == 0 && count[Judge.BAD.ordinal()] == 0;
    }

    /** 理论全 P+ 。 */
    public boolean allPerfect() {
        return count[Judge.PERFECT.ordinal()] == 0
                && count[Judge.EARLY.ordinal()] == 0
                && count[Judge.LATE.ordinal()] == 0
                && count[Judge.BAD.ordinal()] == 0
                && count[Judge.MISS.ordinal()] == 0;
    }
}
