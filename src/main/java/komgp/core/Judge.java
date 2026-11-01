package komgp.core;

/**
 * 五档判定。Early / Late 属于同一档区间，只是命中方向不同，因此 {@code tier} 相同。
 *
 * <pre>
 *   区间   默认宽度   权重
 *   P+     ±20ms     1.00
 *   P      ±45ms     0.95
 *   E/L    ±80ms     0.70   (同一档，按早晚区分显示)
 *   BAD    ±120ms    0.30
 *   MISS   超出      0.00
 * </pre>
 */
public enum Judge {
    PERFECT_PLUS("PERFECT+", "+", 0, 1.00),
    PERFECT("PERFECT", "P", 1, 0.95),
    EARLY("EARLY", "E", 2, 0.70),
    LATE("LATE", "L", 2, 0.70),
    BAD("BAD", "B", 3, 0.30),
    MISS("MISS", "M", 4, 0.00);

    public final String label;
    public final String shortLabel;
    /** 判定档位：0=P+, 1=P, 2=E/L, 3=BAD, 4=MISS，共 5 档。 */
    public final int tier;
    /** 计入准确率的权重。 */
    public final double weight;

    Judge(String label, String shortLabel, int tier, double weight) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.tier = tier;
        this.weight = weight;
    }

    public boolean isGreat() { return tier <= 2; }

    private static final Judge[] VALUES = values();

    public static Judge byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : null;
    }
}
