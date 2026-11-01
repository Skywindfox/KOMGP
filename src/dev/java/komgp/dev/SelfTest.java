package komgp.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import komgp.audio.Decoder;
import komgp.audio.Pcm;
import komgp.cfg.ChartLibrary;
import komgp.core.Chart;
import komgp.core.Judge;
import komgp.core.JudgeWindow;
import komgp.core.PlayState;
import komgp.core.Timing;
import komgp.format.Json;
import komgp.format.ChartEntry;
import komgp.format.ChartIO;
import komgp.format.ChartPackage;
import komgp.format.MalodyLoader;

/**
 * 内置自测（不依赖 JUnit，直接 java 运行）。
 *
 * <p>覆盖：JSON、beat 换算、BPM 时间轴、谱面解析、OGG/MP3/WAV 解码、
 * 判定分级、自动播放满分、曲库扫描。
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path charts = Path.of(args.length > 0 ? args[0] : "charts");
        // 源码包里不含 charts/（演示曲是运行时生成的），这里自动补一份到临时目录，
        // 保证 ./test.sh 在任何地方都能完整跑通。
        if (!hasCharts(charts)) {
            try {
                Path tmp = java.nio.file.Files.createTempDirectory("malody4k-selftest");
                System.out.println("[i] 未找到演示谱面，自动生成到 " + tmp);
                komgp.tools.DemoGen.generateAll(tmp);
                charts = tmp;
            } catch (Throwable t) {
                System.out.println("[!] 生成演示谱面失败（" + t + "），部分用例会被跳过");
            }
        }

        testJson();
        testBeatMath();
        testTiming();
        testJudgeWindow();
        testParseDemo(charts);
        testAudioDecode(charts);
        testAutoplay(charts);
        testLibrary(charts);
        testChartBuilderDedup();
        testTextFormat(charts);

        System.out.println();
        System.out.println("==== 自测结果: " + passed + " 通过, " + failed + " 失败 ====");
        if (failed > 0) System.exit(1);
    }

    private static boolean hasCharts(Path dir) {
        if (!java.nio.file.Files.isDirectory(dir)) return false;
        try (var s = java.nio.file.Files.walk(dir, 3)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return n.endsWith(".mc") || n.endsWith(".mcz");
            });
        } catch (java.io.IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 用例

    private static void testJson() {
        String src = "{\"a\":[1,2,3.5],\"b\":{\"c\":\"x\\u4e2d\"},\"d\":true,\"e\":null,}";
        Object o = Json.parse(src);
        List<Object> a = Json.asArr(Json.get(o, "a"));
        check("JSON 数组", a != null && a.size() == 3);
        check("JSON 嵌套字符串", "x中".equals(Json.str(Json.get(o, "b", "c"), "")));
        check("JSON 布尔", Json.bool(Json.get(o, "d"), false));
        check("JSON 尾逗号容错", Json.asObj(o) != null);

        String back = Json.write(o, true);
        check("JSON 重新解析一致", Json.asObj(Json.parse(back)) != null);
        check("JSON 转义", Json.parse("{\"s\":\"a\\\"b\"}") != null);
    }

    private static void testBeatMath() {
        // Malody: [小节, 分子, 分母] => (小节 + 分子/分母) * 4 拍
        check("beat [0,0,1] = 0", close(beat(new Object[]{0, 0, 1}), 0));
        check("beat [1,0,4] = 4", close(beat(new Object[]{1, 0, 4}), 4));
        check("beat [1,1,4] = 5", close(beat(new Object[]{1, 1, 4}), 5));
        check("beat [1,1,8] = 4.5", close(beat(new Object[]{1, 1, 8}), 4.5));
        check("beat [2,3,16] = 8.75", close(beat(new Object[]{2, 3, 16}), 8.75));
    }

    private static double beat(Object[] arr) {
        try {
            var m = MalodyLoader.class.getDeclaredMethod("beatOf", Object.class);
            m.setAccessible(true);
            return (double) m.invoke(null, List.of(arr));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void testTiming() {
        Timing t = Timing.of(new double[]{0, 16}, new double[]{150, 120}, 32);
        check("150BPM 第 4 拍 = 1600ms", close(t.msAt(4), 1600));
        check("150BPM 第 8 拍 = 3200ms", close(t.msAt(8), 3200));
        // 第 16 拍正好 6400ms，之后 120BPM
        check("变速点时刻", close(t.msAt(16), 6400));
        check("变速后 4 拍 = 2000ms", close(t.msAt(20), 8400));
        check("变速标签", t.bpmLabel().length() > 0);

        Timing def = Timing.ofDefault();
        check("默认 120BPM", close(def.msAt(2), 1000));
    }

    private static void testJudgeWindow() {
        JudgeWindow w = JudgeWindow.standard();
        check("dt=0 -> P+", w.classify(0) == Judge.PERFECT_PLUS);
        check("dt=19 -> P+", w.classify(19) == Judge.PERFECT_PLUS);
        check("dt=20 -> P+", w.classify(20) == Judge.PERFECT_PLUS);
        check("dt=30 -> P", w.classify(30) == Judge.PERFECT);
        check("dt=-60 -> EARLY", w.classify(-60) == Judge.EARLY);
        check("dt=60 -> LATE", w.classify(60) == Judge.LATE);
        check("dt=100 -> BAD", w.classify(100) == Judge.BAD);
        check("dt=121 -> MISS", w.classify(121) == Judge.MISS);
        check("E/L 同档", Judge.EARLY.tier == Judge.LATE.tier);
        check("五档", Judge.MISS.tier == 4);

        JudgeWindow loose = new JudgeWindow(1.5);
        check("练习模式更宽松", loose.classify(60) != Judge.LATE && loose.classify(60).tier <= 1);
    }

    private static Chart firstChart(Path dir) {
        var pkg = ChartIO.load(dir, true);
        if (!pkg.ok()) throw new IllegalStateException("加载失败: " + pkg.error);
        return pkg.charts.get(0).chart;
    }

    private static void testParseDemo(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            check("演示谱面目录存在", false);
            return;
        }
        Path mcz = dir.resolve("01 Neon Pulse.mcz");
        if (!Files.exists(mcz)) {
            check("找到 .mcz", false);
            return;
        }
        ChartPackage pkg = ChartIO.load(mcz, true);
        check(".mcz 解析成功", pkg.ok());
        check(".mcz 含 2 张难度", pkg.charts.size() == 2);
        if (pkg.charts.isEmpty()) return;

        var lc = pkg.charts.get(0);
        Chart c = lc.chart;
        check("曲名", "Neon Pulse".equals(c.title));
        check("中文名", "霓虹脉冲".equals(c.titleOrig));
        check("4K", c.columns == 4);
        System.out.println("    Normal: " + c.noteCount() + " 音符, " + c.holdCount() + " 长条, "
                + c.durationLabel() + ", " + c.timing.bpmLabel() + " BPM");
        check("有音符", c.noteCount() > 150);
        check("有长条", c.holdCount() > 0);
        check("BPM 150", close(c.timing.bpmMain(), 150));
        check("音频已解码", lc.audio != null && lc.audio.length > 1000);
        check("曲绘存在", lc.cover != null && lc.cover.length > 1000);
        check("时长合理", c.durationMs() > 40000 && c.durationMs() < 70000);
        check("音符时间升序", isSorted(c));
        check("音符合法列", allInRange(c));

        // 难度 2 是 Hard
        Chart hard = pkg.charts.get(1).chart;
        check("Hard 难度名", "Hard".equals(hard.difficulty));
        check("Hard 比 Normal 密", hard.noteCount() > c.noteCount());
    }

    private static void testAudioDecode(Path dir) throws Exception {
        Path mc = dir.resolve("02 Midnight Drive.mc");
        if (Files.exists(mc)) {
            var pkg = ChartIO.load(mc, true);
            check("MP3 包解析", pkg.ok());
            if (pkg.ok()) {
                var lc = pkg.charts.get(0);
                check("MP3 有字节", lc.audio != null && lc.audio.length > 10000);
                Pcm pcm = Decoder.decode(lc.audio, lc.audioName);
                check("MP3 解码出非静音", pcm.rms() > 0.005);
                check("MP3 时长合理", pcm.durationMs() > 40000 && pcm.durationMs() < 90000);
                System.out.println("    MP3: " + pcm);
            }
        }
        Path oggMc = dir.resolve("01 Neon Pulse.mcz");
        if (Files.exists(oggMc)) {
            var pkg = ChartIO.load(oggMc, true);
            if (pkg.ok() && !pkg.charts.isEmpty()) {
                var lc = pkg.charts.get(0);
                if (lc.audioName != null && lc.audioName.endsWith(".ogg")) {
                    Pcm pcm = Decoder.decode(lc.audio, lc.audioName);
                    check("OGG 解码出非静音", pcm.rms() > 0.005);
                    check("OGG 时长合理", pcm.durationMs() > 40000 && pcm.durationMs() < 90000);
                    System.out.println("    OGG: " + pcm);
                }
            }
        }
    }

    private static void testAutoplay(Path dir) {
        Path mc = dir.resolve("03 Clockwork Garden.mc");
        if (!Files.exists(mc)) {
            check("找到 WAV 谱面", false);
            return;
        }
        Chart c = firstChart(mc);
        PlayState ps = new PlayState(c, JudgeWindow.standard(), false, true);
        double end = c.lastNoteMs + 1500;
        for (double t = 0; t <= end; t += 4) {
            ps.update(t);
        }
        ps.finalizePending();
        check("AUTO 全 P+", ps.count[Judge.PERFECT_PLUS.ordinal()] == c.noteCount());
        check("AUTO 满分", ps.score() == 1_000_000L);
        check("AUTO 满血", ps.health >= 100);
        check("AUTO 无断长条", ps.holdBreaks == 0);
        check("AUTO 最大连击 = 音符数", ps.maxCombo == c.noteCount());
        check("AUTO 全连", ps.fullCombo() && ps.allPerfect());
        check("结算评级 SS", "SS".equals(ps.grade()));

        // 手动命中：每 10 个音符故意早 30ms
        PlayState h = new PlayState(c, JudgeWindow.standard(), false, false);
        int hit = 0;
        for (int i = 0; i < c.noteCount(); i += 1) {
            boolean early = i % 10 == 0;
            boolean late = !early && i % 7 == 0;
            double at = c.time[i] + (early ? -60 : (late ? 65 : 30));
            if (h.press(c.column[i], at) != null) hit++;
            h.release(c.column[i], at + 100);
            h.update(at + 1);
        }
        check("手动命中数 > 90%", hit > c.noteCount() * 0.9);
        check("手动 Pattern 判定正确", h.count[Judge.PERFECT.ordinal()] > 0);
        check("手动有 Early/Late 记录",
                h.count[Judge.EARLY.ordinal()] + h.count[Judge.LATE.ordinal()] > 0);
    }

    private static void testLibrary(Path dir) {
        ChartLibrary lib = new ChartLibrary(dir);
        lib.scan();
        check("曲库扫描出歌曲", lib.songs.size() >= 3);
        check("曲库扫描出谱面", lib.items.size() >= 4);
        check("按难度排序", lib.songs.stream().allMatch(s -> {
            for (int i = 1; i < s.items.size(); i++) {
                if (s.items.get(i).chart.levelNumber() < s.items.get(i - 1).chart.levelNumber()) return false;
            }
            return true;
        }));
        if (!lib.songs.isEmpty()) {
            var cover = lib.cover(lib.songs.get(0).first());
            check("封面可加载", cover != null && cover.getWidth() > 0);
        }
    }

    private static void testChartBuilderDedup() {
        Chart.Builder b = Chart.builder();
        b.timing = Timing.ofDefault();
        b.add(0, 1000, 1000, false);
        b.add(0, 1000.5, 1000.5, false);  // 重复
        b.add(1, 2000, 0, false);          // 非法长条 end<=start => tap
        b.add(9, 3000, 0, false);          // 越界列，被丢弃
        Chart c = b.build();
        check("去重", c.noteCount() == 2);
        check("非法长条降级为单点", !c.hold[1]);
        check("列越界被裁掉", c.noteCount() == 2);
    }

    private static void testTextFormat(Path dir) throws Exception {
        // 1) 明文谱面：写 -> 读，音符与时间轴要对得上
        komgp.core.Chart.Builder b = komgp.core.Chart.builder();
        b.timing = komgp.core.Timing.of(new double[]{0}, new double[]{150}, 64);
        b.columns = 4;
        b.title = "测试曲目";
        b.artist = "Tester";
        b.difficulty = "Hard";
        b.level = "9";
        for (int i = 0; i < 40; i++) {
            b.add(i % 4, 1000 + i * 125, 1000 + i * 125, false);
        }
        b.add(0, 8000, 8600, true);
        komgp.core.Chart chart = b.build();

        Path tmp = java.nio.file.Files.createTempDirectory("komgp-text");
        Path kmt = tmp.resolve("test.kmt");
        komgp.format.TextChart.save(chart, kmt, "audio.ogg", "cover.png");
        String text = java.nio.file.Files.readString(kmt);
        check("明文谱面首行魔数", text.startsWith("KOMGP CHART 1"));
        check("明文谱面可人工阅读（含注释）", text.contains("# "));

        var pkg = komgp.format.ChartIO.load(kmt, false);
        check("明文谱面可加载", pkg.ok());
        if (pkg.ok()) {
            var c = pkg.charts.get(0).chart;
            check("明文谱面音符数一致", c.noteCount() == chart.noteCount());
            check("明文谱面长条保留", c.holdCount() == 1);
            check("明文谱面标题保留", "测试曲目".equals(c.title));
            check("明文谱面 BPM 保留", Math.abs(c.timing.bpmMain() - 150) < 0.01);
        }

        // 2) 内容识别：把 .kmt 改名成 .txt 也要能认出来
        Path renamed = tmp.resolve("disguised.txt");
        java.nio.file.Files.copy(kmt, renamed);
        check("按内容识别（.txt 伪装）",
                komgp.format.ChartIO.detect(renamed) == komgp.format.ChartIO.Kind.TEXT);
        check("伪装文件可加载", komgp.format.ChartIO.load(renamed, false).ok());

        // 3) Malody 包识别 + 导出后重新加载
        if (dir != null && java.nio.file.Files.isDirectory(dir)) {
            Path mcz = dir.resolve("01 Neon Pulse.mcz");
            if (java.nio.file.Files.exists(mcz)) {
                check("zip 内容识别", komgp.format.ChartIO.detect(mcz)
                        == komgp.format.ChartIO.Kind.ZIP);
                var mp = komgp.format.ChartIO.load(mcz, true);
                check("Malody 包加载", mp.ok());
                if (mp.ok()) {
                    var lc = mp.charts.get(0);
                    Path out = tmp.resolve("export.mc");
                    komgp.format.MalodyWriter.writeMc(out, lc.chart, lc.audio, lc.audioName,
                            lc.cover, "cover.png", lc.chart.timing.bpmMain());
                    var back = komgp.format.ChartIO.load(out, false);
                    check("导出 .mc 可重新加载", back.ok());
                    if (back.ok()) {
                        int a = lc.chart.noteCount();
                        int n = back.charts.get(0).chart.noteCount();
                        check("导出后音符数接近（1/16 量化）", Math.abs(a - n) <= a / 50 + 2);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static boolean isSorted(Chart c) {
        for (int i = 1; i < c.time.length; i++) {
            if (c.time[i] < c.time[i - 1] - 1e-6) return false;
        }
        return true;
    }

    private static boolean allInRange(Chart c) {
        for (int v : c.column) if (v < 0 || v >= c.columns) return false;
        return true;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
