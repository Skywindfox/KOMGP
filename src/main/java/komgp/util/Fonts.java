package komgp.util;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashSet;
import java.util.Set;

/**
 * 字体选择：优先挑一个系统里存在的中文字体，避免中文全变豆腐块。
 */
public final class Fonts {

    private static String family;
    private static boolean probed;

    private static final String[] PREFERRED = {
            "Noto Sans CJK SC", "Source Han Sans SC", "Source Han Sans CN",
            "Microsoft YaHei", "微软雅黑", "PingFang SC", "Hiragino Sans GB",
            "Droid Sans Fallback", "WenQuanYi Micro Hei", "WenQuanYi Zen Hei",
            "SimHei", "黑体", "SimSun", "宋体", "SansSerif"
    };

    private Fonts() {}

    public static synchronized String family() {
        if (!probed) {
            probed = true;
            Set<String> have = new HashSet<>();
            try {
                for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()) {
                    have.add(f);
                }
            } catch (Throwable t) {
                have.clear();
            }
            for (String p : PREFERRED) {
                if (have.contains(p)) {
                    family = p;
                    break;
                }
            }
            if (family == null) family = Font.SANS_SERIF;
        }
        return family;
    }

    public static Font of(int style, float size) {
        return new Font(family(), style, 12).deriveFont(style, size);
    }

    public static Font bold(float size) {
        return of(Font.BOLD, size);
    }

    public static Font plain(float size) {
        return of(Font.PLAIN, size);
    }
}
