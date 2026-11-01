package komgp.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import komgp.audio.Decoder;
import komgp.audio.Pcm;
import komgp.audio.Sfx;
import komgp.cfg.ChartLibrary;
import komgp.core.Chart;
import komgp.core.Timing;
import komgp.format.ChartEntry;
import komgp.format.ChartIO;
import komgp.format.ChartPackage;
import komgp.format.MalodyWriter;
import komgp.format.TextChart;
import komgp.game.GameApp;
import komgp.game.Screen;
import komgp.render.Painter;
import komgp.render.Theme;
import komgp.util.Log;

/**
 * 谱面编辑器（时间轴式）。
 *
 * <p>左键放音符 / 右键删音符，滚轮滚动，Shift+滚轮缩放，空格播放，
 * 存成 KOMGP 明文谱面（.kmt）或导出 Malody 包（.mc）。
 *
 * <p>设计上刻意做成「所见即所得的时间轴」而不是下落预览：
 * 编辑时更关心整段节奏的分布，水平时间轴能一屏看十几秒。
 */
public final class EditorScreen extends Screen {

    private enum Mode { EDIT, PICK_AUDIO }

    private static final double TL_X = 250;
    private static final double TL_W = Theme.W - TL_X - 40;
    private static final double RULER_Y = 100;
    private static final double LANE_Y = 132;
    private static final double LANE_H = 110;
    private static final double HIT_TOL_MS = 45;

    private static final String[] SNAP_LABEL = {"1/4", "1/8", "1/16", "1/32", "自由"};
    private static final double[] SNAP_BEAT = {1, 0.5, 0.25, 0.125, 0};

    /** 一条可编辑的音符。 */
    private static final class EdNote {
        double t;
        int col;
        double len;

        EdNote(double t, int col, double len) {
            this.t = t;
            this.col = col;
            this.len = len;
        }
    }

    private final Path sourcePath;
    private final GameApp app;

    private Mode mode = Mode.EDIT;
    private Chart chart;
    private Timing timing = Timing.ofDefault();
    private final List<EdNote> notes = new ArrayList<>();
    private final List<double[]> undo = new ArrayList<>();

    private String title = "新谱面";
    private String artist = "Unknown";
    private String difficulty = "Normal";
    private String level = "1";
    private String creator = "";

    private Path audioPath;
    private String audioName;
    private BufferedImage cover;
    private Pcm pcm;
    private boolean loading;
    private String loadError;

    private double viewStart;
    private double pxPerMs = 0.085;
    private double cursorMs;
    private boolean playing;
    private int snapIndex = 2;
    private boolean dirty;

    private int hoverCol = -1;
    private double hoverTime;
    private int editField = -1;
    private double escAt = -1e9;

    private final List<Path> audioChoices = new ArrayList<>();
    private int audioSel;
    private ChartLibrary.Item pickItem;

    public EditorScreen(GameApp app, Path source) {
        super(app);
        this.app = app;
        this.sourcePath = source;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onEnter() {
        app.audio.stop();
        if (sourcePath != null) {
            open(sourcePath);
        } else {
            startAudioPicker();
        }
    }

    @Override
    public void onExit() {
        app.audio.stop();
    }

    private void open(Path path) {
        loading = true;
        loadError = null;
        Thread t = new Thread(() -> {
            try {
                ChartPackage pkg = ChartIO.load(path, true);
                if (!pkg.ok()) {
                    loadError = pkg.error;
                    loading = false;
                    return;
                }
                ChartEntry lc = pkg.charts.get(0);
                Chart c = lc.chart;
                synchronized (this) {
                    chart = c;
                    timing = c.timing;
                    title = c.title;
                    artist = c.artist;
                    difficulty = c.difficulty.isBlank() ? "Normal" : c.difficulty;
                    level = c.levelLabel();
                    creator = c.creator;
                    audioName = lc.audioName;
                    notes.clear();
                    for (int i = 0; i < c.noteCount(); i++) {
                        notes.add(new EdNote(c.time[i], c.column[i],
                                c.hold[i] ? c.endTime[i] - c.time[i] : 0));
                    }
                    sortNotes();
                    pcm = null;
                    if (lc.audio != null) pcm = Decoder.decode(lc.audio, lc.audioName);
                    if (lc.cover != null) {
                        cover = komgp.render.ImageUtil.decode(lc.cover);
                    }
                    audioPath = path;
                    dirty = false;
                    cursorMs = 0;
                    viewStart = 0;
                    loading = false;
                }
                app.audio.loadSong(pcm);
                app.audio.setVolume((float) app.settings.volume);
            } catch (Throwable e) {
                Log.error("编辑器载入失败", e);
                loadError = e.toString();
                loading = false;
            }
        }, "editor-loader");
        t.setDaemon(true);
        t.start();
    }

    private void startAudioPicker() {
        mode = Mode.PICK_AUDIO;
        audioChoices.clear();
        audioSel = 0;
        try (var s = Files.walk(app.chartsDir, 4)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".ogg") || n.endsWith(".mp3") || n.endsWith(".wav")
                        || n.endsWith(".flac")) {
                    audioChoices.add(p);
                }
            });
        } catch (Exception e) {
            Log.warn("扫描音频失败: " + e.getMessage());
        }
        audioChoices.sort(Comparator.comparing(Path::toString));
    }

    private void createFromAudio(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Thread t = new Thread(() -> {
            try {
                Pcm p = Decoder.decode(Files.readAllBytes(audio), name);
                synchronized (this) {
                    pcm = p;
                    chart = null;
                    timing = Timing.of(new double[]{0}, new double[]{120}, 4096);
                    title = stem;
                    artist = "Unknown";
                    difficulty = "Normal";
                    level = "1";
                    creator = app.settings.lastChart.isEmpty() ? "" : "";
                    audioName = name;
                    cover = null;
                    notes.clear();
                    notes.add(new EdNote(0, 0, 0));
                    notes.clear();
                    dirty = true;
                    cursorMs = 0;
                    viewStart = 0;
                    loading = false;
                    mode = Mode.EDIT;
                    audioPath = audio;
                }
                app.audio.loadSong(p);
            } catch (Exception e) {
                loadError = "音频解码失败: " + e.getMessage();
                loading = false;
            }
        }, "editor-audio");
        t.setDaemon(true);
        t.start();
        loading = true;
    }

    // ------------------------------------------------------------------ 编辑操作

    private void sortNotes() {
        notes.sort(Comparator.comparingDouble(n -> n.t));
    }

    private void pushUndo() {
        undo.add(new double[]{notes.size(), cursorMs, viewStart});
        // 用快照数组更简单：把当前音符压成扁平数组
        double[] snap = new double[notes.size() * 3];
        for (int i = 0; i < notes.size(); i++) {
            EdNote n = notes.get(i);
            snap[i * 3] = n.t;
            snap[i * 3 + 1] = n.col;
            snap[i * 3 + 2] = n.len;
        }
        undoSnapshot.add(snap);
        if (undoSnapshot.size() > 60) undoSnapshot.remove(0);
    }

    private final List<double[]> undoSnapshot = new ArrayList<>();

    private void doUndo() {
        if (undoSnapshot.isEmpty()) return;
        double[] snap = undoSnapshot.remove(undoSnapshot.size() - 1);
        notes.clear();
        for (int i = 0; i + 2 < snap.length; i += 3) {
            notes.add(new EdNote(snap[i], (int) snap[i + 1], snap[i + 2]));
        }
        dirty = true;
        app.toast("已撤销");
    }

    private double snapMs() {
        double step = SNAP_BEAT[snapIndex];
        if (step <= 0) return 0;
        double beat = timing.beatOfMs(Math.max(0, hoverTime));
        double snapped = Math.round(beat / step) * step;
        return timing.msAt(snapped);
    }

    private EdNote noteAt(int col, double timeMs, double tolMs) {
        EdNote best = null;
        double bestD = Double.MAX_VALUE;
        for (EdNote n : notes) {
            if (n.col != col) continue;
            double d = Math.abs(n.t - timeMs);
            if (d <= tolMs && d < bestD) {
                best = n;
                bestD = d;
            }
        }
        return best;
    }

    private void placeAt(double timeMs, int col) {
        double t = Math.max(0, timeMs);
        pushUndo();
        EdNote hit = noteAt(col, t, HIT_TOL_MS);
        if (hit != null) {
            notes.remove(hit);
            app.toast("删除音符");
        } else {
            notes.add(new EdNote(t, col, 0));
            sortNotes();
            app.sfx.play(Sfx.Kind.TAP);
        }
        dirty = true;
    }

    private void removeAt(double timeMs, int col) {
        EdNote hit = noteAt(col, timeMs, HIT_TOL_MS * 2);
        if (hit == null) return;
        pushUndo();
        notes.remove(hit);
        dirty = true;
        app.sfx.play(Sfx.Kind.MOVE);
    }

    // ------------------------------------------------------------------ 播放

    private void togglePlay() {
        if (playing) {
            cursorMs = Math.max(0, app.audio.songMs());
            app.audio.stop();
            playing = false;
        } else {
            if (pcm == null) {
                app.toast("没有音频，无法试听");
                return;
            }
            double from = Math.max(0, Math.min(cursorMs, pcm.durationMs() - 50));
            app.audio.loadSong(pcm);
            app.audio.playFrom(from, 0);
            playing = true;
        }
    }

    private void seek(double ms) {
        cursorMs = Math.max(0, Math.min(ms, durationMs()));
        viewStart = Math.max(0, Math.min(viewStart, cursorMs));
        if (playing) {
            app.audio.playFrom(cursorMs, 0);
        }
    }

    private double durationMs() {
        if (pcm != null) return pcm.durationMs();
        double last = 0;
        for (EdNote n : notes) last = Math.max(last, n.t + n.len);
        return Math.max(10000, last + 3000);
    }

    private double nowMs() {
        if (playing) {
            double t = app.audio.songMs();
            if (t >= durationMs() - 20) {
                playing = false;
                app.audio.stop();
                return durationMs();
            }
            return t;
        }
        return cursorMs;
    }

    // ------------------------------------------------------------------ 保存

    private void save() {
        try {
            Path target = saveTarget();
            String rel = audioName == null ? "audio.ogg" : audioName;
            double bpm = timing.bpmMain();
            // 用编辑器当前内容构造一个只读 Chart 供写出
            Chart out = buildChart();
            if (out == null) {
                app.toast("没有音符可保存");
                return;
            }
            TextChart.save(out, target, rel, coverName());
            dirty = false;
            app.toast("已保存: " + target.getFileName());
            app.sfx.play(Sfx.Kind.OK);
            app.library.scan();
        } catch (Exception e) {
            app.toast("保存失败: " + e.getMessage());
            Log.error("保存谱面失败", e);
        }
    }

    private void exportMc() {
        try {
            Chart out = buildChart();
            if (out == null) {
                app.toast("没有音符可导出");
                return;
            }
            Path dir = audioPath != null ? audioPath.getParent() : app.chartsDir;
            if (dir == null) dir = app.chartsDir;
            String base = safeName(title + " [" + difficulty + "]");
            Path target = dir.resolve(base + ".mc");
            byte[] audioBytes = audioPath == null ? null : Files.readAllBytes(audioPath);
            byte[] coverBytes = null;
            if (cover != null) {
                var bos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(cover, "png", bos);
                coverBytes = bos.toByteArray();
            }
            MalodyWriter.writeMc(target, out, audioBytes, audioName,
                    coverBytes, coverBytes != null ? "cover.png" : null, timing.bpmMain());
            app.toast("已导出 Malody 包: " + target.getFileName());
            app.sfx.play(Sfx.Kind.OK);
            app.library.scan();
        } catch (Exception e) {
            app.toast("导出失败: " + e.getMessage());
            Log.error("导出 Malody 失败", e);
        }
    }

    private String coverName() {
        return cover != null ? "cover.png" : null;
    }

    private Path saveTarget() {
        Path dir = audioPath != null && audioPath.getParent() != null
                ? audioPath.getParent() : app.chartsDir;
        if (sourcePath != null && sourcePath.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(TextChart.EXT)) {
            return sourcePath;
        }
        return dir.resolve(safeName(title + " [" + difficulty + "]") + TextChart.EXT);
    }

    private static String safeName(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private Chart buildChart() {
        if (notes.isEmpty()) return null;
        Chart.Builder b = Chart.builder();
        b.timing = timing;
        b.columns = 4;
        b.title = title == null || title.isBlank() ? "Untitled" : title;
        b.artist = artist == null || artist.isBlank() ? "Unknown" : artist;
        b.creator = creator == null ? "" : creator;
        b.difficulty = difficulty;
        b.level = level;
        b.audioEntry = audioName;
        b.coverEntry = coverName();
        b.sourcePath = sourcePath == null ? "" : sourcePath.toString();
        for (EdNote n : notes) {
            b.add(n.col, n.t, n.t + n.len, n.len > 0);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public void keyPressed(int code, double songMs) {
        if (mode == Mode.PICK_AUDIO) {
            pickKey(code);
            return;
        }
        if (editField >= 0 && code != KeyEvent.VK_ESCAPE && code != KeyEvent.VK_TAB
                && code != KeyEvent.VK_ENTER) {
            if (code == KeyEvent.VK_BACK_SPACE) {
                String cur = fieldValue(editField);
                if (!cur.isEmpty()) setFieldValue(editField, cur.substring(0, cur.length() - 1));
                return;
            }
            String s = KeyEvent.getKeyText(code);
            if (s.length() == 1) {
                char ch = s.charAt(0);
                if (ch >= 32 && ch < 127) {
                    setFieldValue(editField, fieldValue(editField) + ch);
                }
            }
            return;
        }
        switch (code) {
            case KeyEvent.VK_ESCAPE -> {
                if (dirty && app.wallMs - escAt > 1500) {
                    escAt = app.wallMs;
                    app.toast("有未保存的修改，再按一次 Esc 放弃");
                    return;
                }
                app.go(new SongSelectScreen(app));
            }
            case KeyEvent.VK_SPACE -> togglePlay();
            case KeyEvent.VK_LEFT -> seek(nowMs() - stepMs());
            case KeyEvent.VK_RIGHT -> seek(nowMs() + stepMs());
            case KeyEvent.VK_HOME -> seek(0);
            case KeyEvent.VK_TAB -> editField = (editField + 2) % 5 - 1;
            case KeyEvent.VK_1 -> snapIndex = 0;
            case KeyEvent.VK_2 -> snapIndex = 1;
            case KeyEvent.VK_3 -> snapIndex = 2;
            case KeyEvent.VK_4 -> snapIndex = 3;
            case KeyEvent.VK_5 -> snapIndex = 4;
            case KeyEvent.VK_S -> {
                if (app.input.isCtrl()) save();
            }
            case KeyEvent.VK_F2 -> save();
            case KeyEvent.VK_F3 -> exportMc();
            case KeyEvent.VK_N -> startAudioPicker();
            case KeyEvent.VK_Z -> {
                if (app.input.isCtrl()) doUndo();
            }
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS -> pxPerMs = Math.min(0.6, pxPerMs * 1.25);
            case KeyEvent.VK_MINUS -> pxPerMs = Math.max(0.015, pxPerMs / 1.25);
            case KeyEvent.VK_DELETE -> {
                EdNote n = noteAt(hoverCol, hoverTime, HIT_TOL_MS * 2);
                if (n != null) {
                    pushUndo();
                    notes.remove(n);
                    dirty = true;
                }
            }
            default -> { }
        }
    }

    private double stepMs() {
        double step = SNAP_BEAT[snapIndex];
        if (step <= 0) step = 0.25;
        return timing.msAt(timing.beatOfMs(cursorMs) + step) - cursorMs;
    }

    private String fieldValue(int i) {
        return switch (i) {
            case 0 -> title;
            case 1 -> artist;
            case 2 -> difficulty;
            case 3 -> level;
            default -> creator;
        };
    }

    private void setFieldValue(int i, String v) {
        switch (i) {
            case 0 -> title = v;
            case 1 -> artist = v;
            case 2 -> difficulty = v;
            case 3 -> level = v;
            default -> creator = v;
        }
        dirty = true;
    }

    private void pickKey(int code) {
        switch (code) {
            case KeyEvent.VK_ESCAPE -> app.go(new SongSelectScreen(app));
            case KeyEvent.VK_UP -> audioSel = Math.floorMod(audioSel - 1,
                    Math.max(1, audioChoices.size()));
            case KeyEvent.VK_DOWN -> audioSel = Math.floorMod(audioSel + 1,
                    Math.max(1, audioChoices.size()));
            case KeyEvent.VK_ENTER -> {
                if (!audioChoices.isEmpty()) createFromAudio(audioChoices.get(audioSel));
            }
            default -> { }
        }
    }

    @Override
    public void mousePressed(double x, double y, int button) {
        if (mode == Mode.PICK_AUDIO) {
            if (!audioChoices.isEmpty()) {
                for (int i = 0; i < audioChoices.size(); i++) {
                    double ry = 170 + i * 34;
                    if (x > 340 && x < 940 && y > ry - 24 && y < ry + 6) {
                        audioSel = i;
                        createFromAudio(audioChoices.get(i));
                        return;
                    }
                }
            }
            return;
        }
        if (y >= RULER_Y && y < LANE_Y) {
            seek(xToTime(x));
            return;
        }
        int col = laneAt(y);
        if (col < 0) return;
        double t = snapMsAt(xToTime(x));
        if (button == 3 || button == 2) {
            removeAt(xToTime(x), col);
        } else {
            placeAt(t, col);
        }
    }

    @Override
    public void mouseMoved(double x, double y) {
        hoverCol = laneAt(y);
        hoverTime = xToTime(x);
    }

    @Override
    public void wheel(int notches) {
        if (app.input.isShift()) {
            double anchor = hoverTime;
            pxPerMs = Math.max(0.015, Math.min(0.6, pxPerMs * (notches > 0 ? 1.2 : 1 / 1.2)));
            viewStart = Math.max(0, anchor - (anchor - viewStart) * 0.2);
        } else {
            viewStart = Math.max(0, viewStart - notches * 900);
        }
    }

    private int laneAt(double y) {
        if (y < LANE_Y || y > LANE_Y + 4 * LANE_H) return -1;
        return (int) ((y - LANE_Y) / LANE_H);
    }

    private double xToTime(double x) {
        return viewStart + (x - TL_X) / pxPerMs;
    }

    private double timeToX(double t) {
        return TL_X + (t - viewStart) * pxPerMs;
    }

    private double snapMsAt(double t) {
        double step = SNAP_BEAT[snapIndex];
        if (step <= 0) return Math.max(0, t);
        double beat = timing.beatOfMs(Math.max(0, t));
        return timing.msAt(Math.round(beat / step) * step);
    }

    // ------------------------------------------------------------------ 更新

    @Override
    public void update(double dt, double wallMs) {
        if (playing) {
            double t = nowMs();
            if (t - viewStart * 1.0 > (viewStart + TL_W / pxPerMs) - 400) {
                viewStart = Math.max(0, t - 200);
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(Graphics2D g) {
        g.setColor(Theme.BG);
        g.fillRect(0, 0, Theme.W, Theme.H);
        g.setColor(Theme.alpha(Theme.ACCENT, 12));
        g.fillOval(Theme.W - 340, -140, 460, 460);

        Painter.text(g, "谱面编辑器", 28, 52, Theme.f(26, true), Theme.TEXT);
        String file = sourcePath == null ? "（新谱面）" : sourcePath.getFileName().toString();
        Painter.text(g, file + (dirty ? "  · 未保存" : ""), 28, 76, Theme.small(),
                dirty ? Theme.WARN : Theme.MUTED);

        if (loading) {
            Painter.textC(g, "载入中…", Theme.W / 2.0, Theme.H / 2.0, Theme.h1(), Theme.TEXT);
            return;
        }
        if (loadError != null) {
            Painter.textC(g, "无法打开：" + loadError, Theme.W / 2.0, Theme.H / 2.0,
                    Theme.body(), Theme.DANGER);
            Painter.textC(g, "Esc 返回", Theme.W / 2.0, Theme.H / 2.0 + 30, Theme.small(), Theme.DIM);
            return;
        }
        if (mode == Mode.PICK_AUDIO) {
            drawAudioPicker(g);
            return;
        }

        drawLeftPanel(g);
        drawTimeline(g);
        drawFooter(g);
    }

    private void drawLeftPanel(Graphics2D g) {
        Painter.panel(g, 20, 96, 210, 486, 14, Theme.BG2);

        String[] labels = {"标题", "艺术家", "难度", "等级", "谱师"};
        for (int i = 0; i < labels.length; i++) {
            double y = 130 + i * 56;
            boolean active = editField == i;
            Painter.text(g, labels[i], 36, y, Theme.tiny(), Theme.DIM);
            Painter.round(g, 36, y + 8, 178, 28, 6,
                    active ? Theme.alpha(Theme.ACCENT, 40) : Theme.BG3);
            Painter.strokeRound(g, 36, y + 8, 178, 28, 6,
                    active ? Theme.ACCENT : Theme.LINE, active ? 2f : 1f);
            String v = fieldValue(i);
            Painter.text(g, Painter.ellipsis(g, v, Theme.small(), 166), 44, y + 28,
                    Theme.small(), Theme.TEXT);
            if (active) {
                Painter.fill(g, 44 + Painter.width(g, v, Theme.small()) + 1, y + 14, 2, 16,
                        Theme.ACCENT);
            }
        }

        double y = 424;
        Painter.text(g, "音频", 36, y, Theme.tiny(), Theme.DIM);
        Painter.text(g, Painter.ellipsis(g, audioName == null ? "（无）" : audioName,
                Theme.small(), 178), 36, y + 20, Theme.small(), Theme.MUTED);
        Painter.text(g, "音符", 36, y + 50, Theme.tiny(), Theme.DIM);
        Painter.text(g, String.valueOf(notes.size()), 36, y + 70, Theme.f(20, true), Theme.ACCENT2);
        Painter.text(g, "BPM", 110, y + 50, Theme.tiny(), Theme.DIM);
        Painter.text(g, timing.bpmLabel(), 110, y + 70, Theme.f(20, true), Theme.TEXT);

        Painter.text(g, "网格 " + SNAP_LABEL[snapIndex], 36, 556, Theme.small(), Theme.MUTED);
        Painter.text(g, String.format("缩放 %.3f", pxPerMs), 36, 576, Theme.tiny(), Theme.DIM);
    }

    private void drawTimeline(Graphics2D g) {
        double now = nowMs();
        double spanMs = TL_W / pxPerMs;
        double viewEnd = viewStart + spanMs;

        // 标尺
        Painter.round(g, TL_X, RULER_Y, TL_W, 28, 6, Theme.BG2);
        Painter.strokeRound(g, TL_X, RULER_Y, TL_W, 28, 6, Theme.LINE, 1f);

        g.setClip((int) TL_X - 4, (int) RULER_Y - 20, (int) TL_W + 8, (int) (LANE_Y + 4 * LANE_H));

        // 秒刻度
        double secStep = spanMs > 16000 ? 4 : spanMs > 8000 ? 2 : spanMs > 4000 ? 1 : 0.5;
        for (double s = Math.floor(viewStart / (secStep * 1000)) * secStep * 1000;
             s < viewEnd; s += secStep * 1000) {
            double x = timeToX(s);
            if (x < TL_X - 2 || x > TL_X + TL_W + 2) continue;
            Painter.fill(g, x, RULER_Y + 18, 1, 10, Theme.alpha(Theme.LINE, 200));
            Painter.text(g, String.format("%.1fs", s / 1000.0), x + 3, RULER_Y + 16,
                    Theme.tiny(), Theme.DIM);
        }

        // 拍线 / 小节线
        double step = SNAP_BEAT[snapIndex] <= 0 ? 0.25 : SNAP_BEAT[snapIndex];
        double b0 = Math.floor(timing.beatOfMs(Math.max(0, viewStart)) / step) * step;
        double b1 = timing.beatOfMs(viewEnd) + step;
        int guard = 0;
        for (double b = b0; b <= b1 && guard++ < 4000; b += step) {
            double t = timing.msAt(b);
            double x = timeToX(t);
            if (x < TL_X - 2 || x > TL_X + TL_W + 2) continue;
            boolean bar = Math.abs(b % 4) < 1e-6;
            Painter.fill(g, x, LANE_Y, bar ? 2 : 1, 4 * LANE_H,
                    bar ? Theme.alpha(Theme.LINE, 220) : Theme.alpha(Theme.LINE, 90));
        }

        // 轨道
        for (int c = 0; c < 4; c++) {
            double y = LANE_Y + c * LANE_H;
            boolean hover = c == hoverCol;
            Painter.fill(g, TL_X, y, TL_W, LANE_H - 2,
                    hover ? Theme.alpha(Theme.laneColor(c), 34) : Theme.BG2);
            Painter.fill(g, TL_X, y + LANE_H - 2, TL_W, 2, Theme.alpha(Theme.LINE, 160));
            Painter.text(g, "列 " + (c + 1), TL_X + 8, y + 20, Theme.tiny(),
                    Theme.alpha(Theme.laneColor(c), 210));
        }

        // 音符
        for (EdNote n : notes) {
            double x = timeToX(n.t);
            double w = Math.max(5, n.len * pxPerMs);
            if (x + w < TL_X - 20 || x > TL_X + TL_W + 20) continue;
            double y = LANE_Y + n.col * LANE_H + 22;
            Color c = Theme.laneColor(n.col);
            if (n.len > 0) {
                Painter.round(g, x, y + 24, w, 12, 3, Theme.darken(c, 0.2f));
                Painter.strokeRound(g, x, y + 24, w, 12, 3, Theme.alpha(Theme.SHADOW, 180), 1.5f);
            }
            Painter.round(g, x, y, Math.max(5, 10), 34, 3, c);
            Painter.strokeRound(g, x, y, Math.max(5, 10), 34, 3, Theme.alpha(Theme.SHADOW, 200), 1.5f);
        }

        // 播放头
        double px = timeToX(now);
        if (px >= TL_X - 2 && px <= TL_X + TL_W + 2) {
            Painter.fill(g, px - 1, RULER_Y, 3, 4 * LANE_H + 28, Theme.ACCENT);
            g.setColor(Theme.ACCENT);
            g.fillPolygon(new int[]{(int) px - 6, (int) px + 7, (int) px}, 
                    new int[]{(int) RULER_Y + 2, (int) RULER_Y + 2, (int) RULER_Y + 12}, 3);
        }

        // 悬停位置预览
        if (hoverCol >= 0 && hoverCol < 4) {
            double sx = timeToX(snapMsAt(hoverTime));
            Painter.fill(g, sx, LANE_Y + hoverCol * LANE_H, 2, LANE_H - 2,
                    Theme.alpha(Theme.laneColor(hoverCol), 120));
        }

        g.setClip(null);

        // 时间轴外框
        Painter.strokeRound(g, TL_X, RULER_Y, TL_W, LANE_Y + 4 * LANE_H - RULER_Y, 8,
                Theme.LINE, 2f);
    }

    private void drawFooter(Graphics2D g) {
        double y = LANE_Y + 4 * LANE_H + 34;
        Painter.text(g, "左键放置/删除   右键删除   拖标尺定位   空格播放   滚轮滚动   Shift+滚轮缩放",
                28, y, Theme.small(), Theme.MUTED);
        Painter.text(g, "1-5 网格    ←→ 微移   Tab 切换字段   F2 保存 .kmt   F3 导出 .mc   Ctrl+Z 撤销   Esc 返回",
                28, y + 22, Theme.tiny(), Theme.DIM);
        String state = playing ? "播放中" : "已暂停";
        Painter.textR(g, state, Theme.W - 40, y, Theme.small(),
                playing ? Theme.ACCENT2 : Theme.MUTED);
        Painter.textR(g, String.format("%.1fs / %.1fs", nowMs() / 1000, durationMs() / 1000),
                Theme.W - 40, y + 22, Theme.tiny(), Theme.DIM);
    }

    private void drawAudioPicker(Graphics2D g) {
        Painter.textC(g, "新建谱面：选择音频", Theme.W / 2.0, 110, Theme.f(26, true), Theme.TEXT);
        Painter.textC(g, "把音频文件放进 charts/ 目录后按 N 重新扫描",
                Theme.W / 2.0, 140, Theme.small(), Theme.MUTED);
        Painter.panel(g, 320, 160, 640, 420, 14, Theme.BG2);
        if (audioChoices.isEmpty()) {
            Painter.textC(g, "没有找到音频文件（.ogg / .mp3 / .wav）",
                    Theme.W / 2.0, 360, Theme.body(), Theme.WARN);
        } else {
            g.setClip(340, 172, 600, 396);
            for (int i = 0; i < audioChoices.size(); i++) {
                double y = 186 + i * 34;
                if (y > 580) break;
                boolean sel = i == audioSel;
                if (sel) {
                    Painter.round(g, 340, y - 22, 600, 30, 6, Theme.alpha(Theme.ACCENT, 40));
                    Painter.strokeRound(g, 340, y - 22, 600, 30, 6, Theme.ACCENT, 1.5f);
                }
                Path p = audioChoices.get(i);
                Painter.text(g, Painter.ellipsis(g, p.getFileName().toString(), Theme.small(), 560),
                        352, y, Theme.small(), sel ? Theme.TEXT : Theme.MUTED);
            }
            g.setClip(null);
        }
        Painter.textC(g, "↑↓ 选择   Enter 确定   Esc 返回", Theme.W / 2.0, 610,
                Theme.tiny(), Theme.DIM);
    }
}
