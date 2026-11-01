package komgp.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 极轻量的分段计时器：只在开发基准里开启，生产路径开销可忽略（几次 nanoTime）。 */
public final class Prof {

    public static boolean enabled = false;
    private static final Map<String, long[]> ACC = new LinkedHashMap<>();
    private static long lastDump = System.nanoTime();
    private static int frames;

    private Prof() {}

    public static long start() {
        return enabled ? System.nanoTime() : 0;
    }

    public static void add(String key, long t0) {
        if (!enabled || t0 == 0) return;
        long[] a = ACC.computeIfAbsent(key, k -> new long[2]);
        a[0] += System.nanoTime() - t0;
        a[1]++;
    }

    public static void frame() {
        if (!enabled) return;
        frames++;
        long now = System.nanoTime();
        if (now - lastDump > 2_000_000_000L) {
            System.out.println("---- 分段耗时 (每帧均值) ----");
            for (Map.Entry<String, long[]> e : ACC.entrySet()) {
                long[] a = e.getValue();
                System.out.printf("  %-14s %7.3f ms%n", e.getKey(), a[0] / 1e6 / Math.max(1, a[1]));
            }
            System.out.printf("  %-14s %7.3f ms  (帧数 %d)%n", "TOTAL", 0.0, frames);
            ACC.clear();
            frames = 0;
            lastDump = now;
        }
    }

    public static void reset() {
        ACC.clear();
        frames = 0;
        lastDump = System.nanoTime();
    }
}
