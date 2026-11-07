package komgp.dev;

import java.nio.file.Path;

import komgp.core.Chart;
import komgp.format.ChartEntry;
import komgp.format.ChartIO;
import komgp.format.ChartPackage;

/** 把一张谱面的解析结果打出来，用于排查"某张谱面能不能读"。 */
public final class ChartDump {

    private ChartDump() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法: ChartDump <谱面文件>");
            return;
        }
        Path p = Path.of(args[0]);
        System.out.println("文件: " + p);
        System.out.println("识别: " + ChartIO.kindName(ChartIO.detect(p)));
        ChartPackage pkg = ChartIO.load(p, true);
        if (!pkg.ok()) {
            System.out.println("✗ 加载失败: " + pkg.error);
            System.exit(1);
        }
        System.out.println("包内谱面数: " + pkg.charts.size() + "（格式 " + pkg.format + "）");
        for (ChartEntry e : pkg.charts) {
            Chart c = e.chart;
            System.out.println("\n── " + c.displayTitle() + "  [" + c.difficulty + " / Lv." + c.levelLabel() + "]");
            System.out.printf("   列数 %d  音符 %d  长条 %d  时长 %s  BPM %s  谱师 %s%n",
                    c.columns, c.noteCount(), c.holdCount(), c.durationLabel(),
                    c.timing.bpmLabel(), c.creator);
            System.out.printf("   时间轴: %d 个速度点, 首 %.0fms 末 %.0fms%n",
                    c.timing.changeCount(), c.time[0], c.time[c.noteCount() - 1]);
            System.out.printf("   音频: %s (%d 字节)  曲绘: %s (%d 字节)%n",
                    e.audioName, e.audio == null ? 0 : e.audio.length,
                    c.coverEntry, e.cover == null ? 0 : e.cover.length);
            int[] perCol = new int[c.columns];
            for (int i = 0; i < c.noteCount(); i++) perCol[c.column[i]]++;
            StringBuilder sb = new StringBuilder("   每列音符:");
            for (int i = 0; i < perCol.length; i++) sb.append(' ').append(perCol[i]);
            System.out.println(sb);
            if (c.warning != null && !c.warning.isBlank()) {
                System.out.println("   注意: " + c.warning);
            }
        }
    }
}
