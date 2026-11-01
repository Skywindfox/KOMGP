package komgp.format;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import komgp.core.Chart;

/**
 * 把谱面导出成 Malody 的 {@code .mc}（ZIP：chart.json + 音频 + 曲绘）。
 *
 * <p>Malody 的 beat 是 {@code [小节, 分子, 分母]}，这里用 1/16 音符精度反算，
 * 足够覆盖绝大多数 4K 谱面（16 分以内的时值都能精确表示）。
 */
public final class MalodyWriter {

    private MalodyWriter() {}

    public static Path writeMc(Path out, Chart chart, byte[] audio, String audioName,
                               byte[] cover, String coverName, double bpm) throws IOException {
        Map<String, Object> root = buildJson(chart, audioName, coverName, bpm);
        byte[] json = Json.write(root, true).getBytes(StandardCharsets.UTF_8);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            put(zos, "chart.json", json);
            if (audio != null && audioName != null) put(zos, audioName, audio);
            if (cover != null && coverName != null) put(zos, coverName, cover);
        }
        return out;
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static Map<String, Object> buildJson(Chart chart, String audioName, String coverName,
                                                 double bpm) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("creator", chart.creator == null || chart.creator.isBlank() ? "KOMGP" : chart.creator);
        meta.put("version", chart.difficulty == null || chart.difficulty.isBlank()
                ? "Normal" : chart.difficulty);
        if (chart.level != null && !chart.level.isBlank()) meta.put("level", chart.level);
        meta.put("preview", 0);
        meta.put("mode", 0);
        meta.put("time", 0);
        if (coverName != null) {
            meta.put("cover", coverName);
            meta.put("background", coverName);
        }
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("title", chart.title);
        if (chart.titleOrig != null && !chart.titleOrig.isBlank()) song.put("titleorg", chart.titleOrig);
        song.put("artist", chart.artist);
        meta.put("song", song);
        Map<String, Object> modeExt = new LinkedHashMap<>();
        modeExt.put("column", chart.columns);
        modeExt.put("bar_begin", 0);
        modeExt.put("speed", 0);
        meta.put("mode_ext", modeExt);
        if (audioName != null) meta.put("audio", audioName);

        // 时间轴：沿用原谱面的速度点
        List<Object> time = new ArrayList<>();
        int points = Math.max(1, chart.timing.changeCount());
        for (int i = 0; i < points; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("beat", beatOf(chart.timing.beatAt(i)));
            t.put("bpm", chart.timing.bpmAt(i));
            time.add(t);
        }

        List<Object> notes = new ArrayList<>();
        List<Object> extra = new ArrayList<>();
        int n = chart.noteCount();
        for (int i = 0; i < n; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("beat", beatOf(chart.timing.beatOfMs(chart.time[i])));
            if (chart.hold[i]) {
                m.put("endbeat", beatOf(chart.timing.beatOfMs(chart.endTime[i])));
                m.put("x", chart.column[i]);
                m.put("type", 1);
                extra.add(m);
            } else {
                m.put("x", chart.column[i]);
                m.put("ex", 0);
                notes.add(m);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta);
        root.put("time", time);
        root.put("note", notes);
        root.put("extra", extra);
        return root;
    }

    /** 绝对拍数 -> Malody 的 [小节, 分子, 分母]（1/16 精度）。 */
    private static List<Object> beatOf(double beat) {
        double b = Math.max(0, beat);
        int bar = (int) Math.floor(b / 4.0);
        double inBar = b - bar * 4.0;
        int num = (int) Math.round(inBar * 4.0); // 一小节 4 拍 -> 16 分音符
        int den = 16;
        while (num >= 16) {
            num -= 16;
            bar++;
        }
        // 约分
        int g = gcd(Math.abs(num), den);
        return List.of(bar, num / g, den / g);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }
}
