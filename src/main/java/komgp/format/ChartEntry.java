package komgp.format;

import komgp.core.Chart;

/**
 * 一张已加载的谱面 + 它自带的媒体数据（音频 / 曲绘）。
 *
 * <p>Malody 包里媒体是 zip 条目，明文谱面里媒体是同目录文件，
 * 这里统一成同一份结构，上层不用关心来源。
 */
public final class ChartEntry {

    public final Chart chart;
    public final byte[] audio;
    public final String audioName;
    public final byte[] cover;
    public final byte[] background;
    /** 媒体缺失时的说明（null 表示完整）。 */
    public final String mediaNote;

    public ChartEntry(Chart chart, byte[] audio, String audioName,
                      byte[] cover, byte[] background, String mediaNote) {
        this.chart = chart;
        this.audio = audio;
        this.audioName = audioName;
        this.cover = cover;
        this.background = background;
        this.mediaNote = mediaNote;
    }
}
