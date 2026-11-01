package komgp.format;

import java.nio.file.Path;
import java.util.List;

/** 一个物理文件里加载出来的全部谱面（.mcz 可能有多张难度）。 */
public final class ChartPackage {

    public final Path path;
    public final List<ChartEntry> charts;
    public final String error;
    /** 来源格式，仅用于日志/界面显示。 */
    public final String format;

    public ChartPackage(Path path, List<ChartEntry> charts, String error, String format) {
        this.path = path;
        this.charts = charts;
        this.error = error;
        this.format = format;
    }

    public boolean ok() {
        return error == null && !charts.isEmpty();
    }

    public static ChartPackage failed(Path path, String format, String error) {
        return new ChartPackage(path, List.of(), error, format);
    }
}
