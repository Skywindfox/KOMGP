package komgp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import komgp.game.GameApp;
import komgp.tools.DemoGen;
import komgp.util.Fonts;
import komgp.util.Log;

/**
 * KOMGP —— KitsuneOS Music Game Player 入口。
 *
 * <pre>
 *   komgp                       正常启动
 *   komgp --gen-demo            重新生成演示谱面后启动
 *   komgp --charts /path        指定曲库目录
 *   komgp --edit chart.kmt      直接用谱面编辑器打开某个谱面
 *   komgp some.kmt|some.mc      导入谱面并启动
 * </pre>
 */
public final class Main {

    public static final String NAME = "KOMGP";
    public static final String FULL_NAME = "KitsuneOS Music Game Player";
    public static final String VERSION = "v0.1-test";

    public static void main(String[] args) throws Exception {
        Path base = Path.of(System.getProperty("user.dir"));
        boolean genDemo = false;
        boolean autoDemo = true;
        Path editTarget = null;
        boolean forceEditor = false;
        List<Path> imports = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--charts", "-c" -> {
                    if (i + 1 < args.length) base = Path.of(args[++i]);
                }
                case "--gen-demo" -> genDemo = true;
                case "--no-demo" -> autoDemo = false;
                case "--edit" -> {
                    forceEditor = true;
                    if (i + 1 < args.length) editTarget = Path.of(args[++i]);
                }
                case "--version", "-v" -> {
                    System.out.println(NAME + " " + VERSION + "  (" + FULL_NAME + ")");
                    return;
                }
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                default -> {
                    if (a.startsWith("-")) {
                        System.err.println("未知参数: " + a);
                    } else {
                        imports.add(Path.of(a));
                    }
                }
            }
        }

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        if (System.getProperty("sun.java2d.uiScale") == null) {
            System.setProperty("sun.java2d.uiScale", "1.0");
        }

        GameApp app = new GameApp(base);
        Log.info(NAME + " " + VERSION + " · " + FULL_NAME);
        Log.info("工作目录: " + app.baseDir);
        Log.info("曲库目录: " + app.chartsDir);

        if (genDemo) {
            DemoGen.generateAll(app.chartsDir);
        }
        for (Path p : imports) {
            if (app.library.importFile(p)) Log.info("已导入: " + p.getFileName());
        }

        app.library.scan();
        if (app.library.items.isEmpty() && autoDemo) {
            Log.info("曲库里没有谱面，自动生成演示曲目…");
            DemoGen.generateAll(app.chartsDir);
            app.library.scan();
        }

        Fonts.family(); // 预热字体，避免首帧卡顿

        if (editTarget != null) {
            app.pendingEdit = editTarget;
        } else if (forceEditor) {
            app.pendingEditor = true;
        }
        app.start();
    }

    private static void printHelp() {
        System.out.println(NAME + " " + VERSION + " · " + FULL_NAME + """

                用法: komgp [选项] [谱面文件...]

                  --charts <dir>   指定曲库目录（默认 ./charts）
                  --edit <file>    用谱面编辑器打开（.kmt / .mc / .mcz）
                  --gen-demo       重新生成内置演示谱面后启动
                  --no-demo        曲库为空时不自动生成演示谱面
                  -v, --version    版本
                  -h, --help       帮助

                操作:
                  D F J K          4K 按键（可在设置里改）
                  Esc              暂停 / 返回
                  [ ]              微调本曲延时
                  E                在选曲页打开谱面编辑器
                  F11              全屏

                谱面格式:
                  .kmt     KOMGP 明文谱面（可直接手改）
                  .mcz     Malody 包（当前版本）
                  只支持这两种，按文件内容识别。
                """);
    }
}
