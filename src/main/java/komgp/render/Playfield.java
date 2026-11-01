package komgp.render;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import komgp.core.Chart;
import komgp.core.Judge;
import komgp.core.PlayState;

/**
 * 下落区渲染。
 *
 * <p>坐标：音符 y 由「剩余时间」线性决定，因此改变下落速度只是改 pxPerMs，
 * 不需要动任何音符数据；变速 BPM 也不影响手感（这正是 Malody 的做法）。
 */
public final class Playfield {

    public final int columns;
    public final double laneW;
    public final double x0;
    public final double judgeY;

    /** 每毫秒下落的逻辑像素。 */
    public static final double PX_PER_MS_BASE = 0.40;

    public Playfield(int columns) {
        this.columns = columns;
        this.laneW = columns <= 4 ? Theme.LANE_W : Math.max(76, 560.0 / columns);
        this.x0 = (Theme.W - columns * laneW) / 2.0;
        this.judgeY = Theme.JUDGE_Y;
    }

    public double laneX(int col) { return x0 + col * laneW; }

    public double laneCenter(int col) { return x0 + (col + 0.5) * laneW; }

    public double right() { return x0 + columns * laneW; }

    // ------------------------------------------------------------------ 背景层

    private java.awt.image.BufferedImage scene;
    private int sceneKey = -1;

    /**
     * 把「背景 + 底板 + 轨道 + 分隔线 + 上下渐变」烘焙成一张不透明的全屏图。
     *
     * <p>关键优化：这些内容都是静态的，如果每帧分别画，等于每帧做一次
     * 全屏贴图 + 一次半透明大区域混合（实测 3.4ms）；烘焙后只剩一次不透明拷屏（0.95ms）。
     */
    public java.awt.image.BufferedImage scene(java.awt.image.BufferedImage backdrop) {
        int key = (int) Math.round(Painter.scale * 1000) * 32 + columns
                + (backdrop == null ? 0 : System.identityHashCode(backdrop) * 31);
        if (key == sceneKey && scene != null) return scene;
        sceneKey = key;

        int dw = Painter.devW;
        int dh = Painter.devH;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(dw, dh,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (backdrop != null) {
            g.drawImage(backdrop, 0, 0, null);
        } else {
            g.setColor(Theme.BG);
            g.fillRect(0, 0, dw, dh);
        }
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(Painter.scale, Painter.scale);

        double right = right();
        // 硬阴影 + 实色底板 + 描边
        Painter.round(g, x0 - 12 + 6, -24 + 6, right - x0 + 24, judgeY + 96, 16,
                Theme.alpha(Theme.SHADOW, 180));
        Painter.round(g, x0 - 12, -24, right - x0 + 24, judgeY + 96, 16, Theme.BG2);
        Painter.strokeRound(g, x0 - 12, -24, right - x0 + 24, judgeY + 96, 16, Theme.LINE, 2f);

        // 轨道：交替纯色
        for (int c = 0; c < columns; c++) {
            double x = laneX(c);
            Painter.fill(g, x, 0, laneW, judgeY,
                    c % 2 == 0 ? Theme.BG3 : Theme.mix(Theme.BG3, Theme.BG, 0.55f));
        }
        // 分隔线
        for (int c = 0; c <= columns; c++) {
            double x = x0 + c * laneW;
            Painter.fill(g, x - 1, 0, 2, judgeY, Theme.alpha(Theme.LINE, 190));
        }
        // 顶部实色压条（替代渐变淡出）
        Painter.fill(g, x0, 0, right - x0, 26, Theme.alpha(Theme.BG, 210));
        // 判定线下方：深色带 + 车道延续
        Painter.fill(g, x0, judgeY, right - x0, judgeY + 104 - judgeY, Theme.alpha(Theme.BG, 200));
        for (int c = 0; c < columns; c++) {
            double x = laneX(c);
            Painter.fill(g, x + laneW / 2 - 1, judgeY + 8, 2, 60,
                    Theme.alpha(Theme.LINE, 120));
        }
        // 左右描边
        Painter.fill(g, x0 - 2, 0, 2, judgeY, Theme.alpha(Theme.LINE, 200));
        Painter.fill(g, right, 0, 2, judgeY, Theme.alpha(Theme.LINE, 200));
        g.dispose();
        scene = img;
        return img;
    }

    /** 每帧变化的部分：按键光 + 小节线。 */
    public void drawDynamic(Graphics2D g, PlayState ps, double judgeNow, double pxPerMs) {
        double right = right();
        if (ps != null) {
            // 按键光
            if (ps.keyDown != null) {
                for (int c = 0; c < columns && c < ps.keyDown.length; c++) {
                    if (!ps.keyDown[c]) continue;
                    Color accent = Theme.laneColor(c);
                    double x = laneX(c);
                    Painter.fill(g, x, judgeY - 200, laneW, 200, Theme.alpha(accent, 34));
                    Painter.fill(g, x, judgeY - 46, laneW, 46, Theme.alpha(accent, 60));
                }
            }
            // 小节线
            Chart chart = ps.chart;
            double[] bars = chart.timing.barTimes();
            int lo = lowerBound(bars, judgeNow - judgeY / pxPerMs - 50);
            for (int i = lo; i < bars.length; i++) {
                double t = bars[i];
                if (t > judgeNow + 80) break;
                double y = judgeY - (t - judgeNow) * pxPerMs;
                if (y < -4 || y > judgeY + 4) continue;
                Painter.fill(g, x0, y, right - x0, 1, new Color(120, 150, 200, 30));
            }
        }
    }

    public void drawJudgeLine(Graphics2D g, double pulse) {
        double right = right();
        double y = judgeY;
        // 判定线：一条 4px 实色亮条 + 一条 2px 深色底
        Painter.fill(g, x0, y + 4, right - x0, 3, Theme.alpha(Theme.SHADOW, 200));
        Painter.fill(g, x0, y, right - x0, 4, Theme.ACCENT2);
        if (pulse > 0.02) {
            Painter.fill(g, x0, y - 10, right - x0, 10,
                    Theme.alpha(Theme.ACCENT2, (int) (70 * pulse)));
        }
    }

    // ------------------------------------------------------------------ 音符

    public void drawNotes(Graphics2D g, PlayState ps, double judgeNow, double pxPerMs,
                          double visualOffset, boolean effects) {
        Chart chart = ps.chart;
        NoteGfx.ensure(Painter.scale, columns);
        double noteW = laneW - Theme.NOTE_INSET * 2;
        double noteH = Theme.NOTE_H;

        double from = judgeNow - 80 / pxPerMs;
        double to = judgeNow + judgeY / pxPerMs + 40;
        int i = ps.firstVisibleFrom(from);
        int n = chart.noteCount();

        for (; i < n; i++) {
            double t = chart.time[i];
            if (t > to) break;
            boolean hold = chart.hold[i];
            double end = chart.endTime[i];
            if (end < from) continue;

            int col = chart.column[i];
            double cx = laneCenter(col);
            double yHead = yOf(t, judgeNow, pxPerMs, visualOffset);
            double yEnd = yOf(end, judgeNow, pxPerMs, visualOffset);
            boolean judged = ps.judged(i);
            boolean holding = hold && judged && !ps.isHolding(i);

            if (hold && (!judged || holding)) {
                drawHoldBody(g, cx, yHead, yEnd, noteW * 0.62, Theme.laneColor(col));
            }

            if (judged) {
                if (holding && yEnd < judgeY + 30) {
                    // 长条尾部已经越过判定线：画一个小尾巴
                    Painter.round(g, cx - noteW * 0.31, Math.max(yEnd, judgeY - 2),
                            noteW * 0.62, 3, 1.5, Theme.alpha(Theme.laneColor(col), 160));
                }
                continue;
            }
            if (yHead < -noteH || yHead > judgeY + noteH) continue;

            BufferedImage spr = hold ? NoteGfx.head(col) : NoteGfx.tap(col);
            if (spr == null) continue;
            double x = cx - noteW / 2;
            Painter.blitExact(g, spr, x, yHead - noteH / 2);

            // 2D 风格：临近判定线时给一串实色小刻度，而不是发光
            if (effects && yHead > judgeY - 80 && yHead < judgeY + 8) {
                Painter.fill(g, cx - noteW / 2, judgeY - 26, noteW, 3,
                        Theme.alpha(Theme.laneColor(col), 120));
            }
        }
    }

    private void drawHoldBody(Graphics2D g, double cx, double yHead, double yEnd, double w, Color accent) {
        double top = Math.min(yHead, yEnd);
        double bottom = Math.max(yHead, yEnd);
        if (bottom < -20 || top > judgeY + 20) return;
        top = Math.max(top, -20);
        bottom = Math.min(bottom, judgeY + 6);
        double h = bottom - top;
        if (h < 1) return;
        // 主体：纯色 + 深色描边（2D）
        Painter.round(g, cx - w / 2, top, w, h, 4, Theme.darken(accent, 0.28f));
        Painter.strokeRound(g, cx - w / 2, top, w, h, 4, Theme.alpha(Theme.SHADOW, 190), 2f);
        Painter.fill(g, cx - w / 2 + 4, top + 3, 4, Math.max(0, h - 6),
                Theme.mix(accent, Color.WHITE, 0.35f));
    }

    public double yOf(double noteTime, double judgeNow, double pxPerMs, double visualOffset) {
        return judgeY - (noteTime - judgeNow + visualOffset) * pxPerMs;
    }

    // ------------------------------------------------------------------ 特效

    public void drawEffects(Graphics2D g, PlayState ps, double nowMs, boolean enabled) {
        if (!enabled) return;
        double life = 420;
        double noteW = laneW - Theme.NOTE_INSET * 2;
        for (PlayState.Fx fx : ps.fx) {
            if (!fx.valid) continue;
            double age = nowMs - fx.at;
            if (age < 0 || age > life) continue;
            double k = age / life;
            double cx = laneCenter(fx.col);
            if (fx.judge == Judge.MISS) {
                BufferedImage x = NoteGfx.missMark();
                if (x != null) {
                    int s = (int) Math.round(14 + 6 * k);
                    g.setComposite(java.awt.AlphaComposite.getInstance(
                            java.awt.AlphaComposite.SRC_OVER, (float) (1 - k)));
                    g.drawImage(x, (int) Math.round(cx - s / 2.0),
                            (int) Math.round(judgeY + 14 + k * 20), s, s, null);
                    g.setComposite(java.awt.AlphaComposite.SrcOver);
                }
                continue;
            }
            Color c = Theme.judgeColor(fx.judge);
            BufferedImage glow = NoteGfx.glow(fx.col);
            if (glow != null) {
                double w = glow.getWidth() / Painter.scale;
                double h = glow.getHeight() / Painter.scale;
                g.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, (float) Math.max(0, 0.75 * (1 - k))));
                Painter.blitExact(g, glow, cx - w / 2.0, judgeY - h / 2.0);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
            }
            // 命中线闪光
            double lw = noteW * (0.4 + 0.6 * (1 - k));
            Painter.fill(g, cx - lw / 2, judgeY - 1.5, lw, 3,
                    Painter.alpha(c, (int) (200 * (1 - k))));
        }
    }

    private static int lowerBound(double[] arr, double v) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
