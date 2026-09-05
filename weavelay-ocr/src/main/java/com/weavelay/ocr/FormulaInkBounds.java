package com.weavelay.ocr;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 公式框与⌀/∅同形墨迹、以及贴边公差下标的扩框。
 * <p>
 * 左扩：框左缘已有一定墨迹则不扩（⌀已在框内）；否则只并最贴左缘的一团，
 * 且在团内从右往左找白缝切开（避免验⌀粘连时带上汉字）。
 * 右扩：并入右缘紧贴的小团墨迹（公差下标），避免 OCR 捡成尾随 {@code 0}。
 */
public final class FormulaInkBounds {

    private static final float LOOK_H_RATIO = 0.9f;
    private static final int ADJACENT_GAP_PX = 4;
    private static final float MAX_GLYPH_W_RATIO = 0.95f;
    private static final float MIN_Y_OVERLAP_RATIO = 0.25f;
    /** 左缘内已有墨迹：降低阈值，⌀ 稍淡/稍窄也能判定「已在框内」。 */
    private static final float OWN_LEFT_W_RATIO = 0.18f;
    private static final float OWN_LEFT_H_RATIO = 0.32f;
    private static final float OWN_LEFT_STRIP_W_RATIO = 0.28f;
    private static final float OWN_LEFT_DARK_RATIO = 0.055f;
    /** 左扩最多吞一字宽（直径量级）；粘连时更靠白缝切开。 */
    private static final float MAX_EXPAND_W_RATIO = 0.45f;
    private static final float TRAIL_LOOK_H_RATIO = 0.55f;
    private static final float MAX_TRAIL_GLYPH_W_RATIO = 0.60f;
    private static final int DARK_THRESH = 140;

    private FormulaInkBounds() {}

    public record Rect(int x, int y, int w, int h) {
        public int endX() { return x + w; }
        public int endY() { return y + h; }
    }

    /** 左吞⌀（必要时）+ 右吞公差下标残笔。 */
    public static Rect expandForDiameter(BufferedImage cell, int x, int y, int w, int h) {
        Rect r = expandLeadingInk(cell, x, y, w, h);
        return expandTrailingInk(cell, r.x(), r.y(), r.w(), r.h());
    }

    public static Rect expandLeadingInk(BufferedImage cell, int x, int y, int w, int h) {
        if (cell == null || w <= 1 || h <= 1) {
            return new Rect(x, y, w, h);
        }
        int imgW = cell.getWidth();
        int imgH = cell.getHeight();
        x = clamp(x, 0, imgW - 1);
        y = clamp(y, 0, imgH - 1);
        w = Math.min(w, imgW - x);
        h = Math.min(h, imgH - y);
        if (w <= 1 || h <= 1 || x <= 0) {
            return new Rect(x, y, w, h);
        }

        if (leftInteriorOwnsWideGlyph(cell, x, y, w, h)) {
            return new Rect(x, y, w, h);
        }

        int look = Math.min(x, Math.max(8, Math.round(h * LOOK_H_RATIO)));
        int maxGlyphW = Math.max(10, Math.round(h * MAX_GLYPH_W_RATIO));
        int padY = Math.max(1, Math.round(h * 0.08f));
        int roiX0 = x - look;
        int roiY0 = Math.max(0, y - padY);
        int roiX1 = x;
        int roiY1 = Math.min(imgH, y + h + padY);
        if (roiX1 <= roiX0 || roiY1 <= roiY0) {
            return new Rect(x, y, w, h);
        }

        List<int[]> comps = componentsInRoi(cell, roiX0, roiY0, roiX1, roiY1);

        int minYOverlap = Math.max(2, Math.round(h * MIN_Y_OVERLAP_RATIO));
        int[] nearest = null;
        int nearestRight = Integer.MIN_VALUE;
        int nearestW = Integer.MAX_VALUE;
        for (int[] bb : comps) {
            int absMinX = roiX0 + bb[0];
            int absMaxX = roiX0 + bb[2];
            int absMinY = roiY0 + bb[1];
            int absMaxY = roiY0 + bb[3];
            int glyphW = absMaxX - absMinX + 1;
            if (glyphW <= 1 || glyphW > maxGlyphW) {
                continue;
            }
            if (absMaxX < x - ADJACENT_GAP_PX) {
                continue;
            }
            int oy0 = Math.max(absMinY, y);
            int oy1 = Math.min(absMaxY + 1, y + h);
            if (oy1 - oy0 < minYOverlap) {
                continue;
            }
            if (absMaxX > nearestRight
                    || (absMaxX == nearestRight && glyphW < nearestW)) {
                nearestRight = absMaxX;
                nearestW = glyphW;
                nearest = new int[]{absMinX, absMaxX, absMinY, absMaxY, glyphW};
            }
        }

        if (nearest == null) {
            return new Rect(x, y, w, h);
        }
        int absMinX = nearest[0];
        int absMaxX = nearest[1];
        int absMinY = nearest[2];
        int absMaxY = nearest[3];
        int maxExpand = Math.max(10, Math.round(h * MAX_EXPAND_W_RATIO));
        // 从右往左找白缝：验|⌀ 粘连时切断；否则最多保留直径宽
        int bestLeft = cutAtGapOrCap(cell, absMinX, absMaxX, absMinY, absMaxY, maxExpand);
        if (bestLeft >= x) {
            return new Rect(x, y, w, h);
        }
        int nx = Math.max(0, bestLeft);
        int nw = (x + w) - nx;
        return new Rect(nx, y, nw, h);
    }

    /**
     * 在墨迹团 [left,right] 内从右扫描：遇近空列且右侧已有墨迹 → 白缝切开；
     * 否则右对齐最多 keepW。
     */
    static int cutAtGapOrCap(
            BufferedImage cell, int left, int right, int y0, int y1, int keepW) {
        int bandH = Math.max(1, y1 - y0 + 1);
        int emptyMax = Math.max(1, bandH / 12);
        int inkSeen = 0;
        int gapCol = -1;
        for (int xx = right; xx >= left; xx--) {
            int dark = 0;
            for (int yy = y0; yy <= y1; yy++) {
                if (xx >= 0 && yy >= 0 && xx < cell.getWidth() && yy < cell.getHeight()
                        && isDark(cell.getRGB(xx, yy))) {
                    dark++;
                }
            }
            if (dark <= emptyMax) {
                if (inkSeen > 0) {
                    gapCol = xx;
                    break;
                }
            } else {
                inkSeen++;
                if (inkSeen >= keepW && gapCol < 0) {
                    // 尚未遇缝但已够直径宽：停在 right-keepW+1
                    return Math.max(left, right - keepW + 1);
                }
            }
        }
        if (gapCol >= 0) {
            return Math.min(right, gapCol + 1);
        }
        return Math.max(left, right - keepW + 1);
    }

    public static Rect expandTrailingInk(BufferedImage cell, int x, int y, int w, int h) {
        if (cell == null || w <= 1 || h <= 1) {
            return new Rect(x, y, w, h);
        }
        int imgW = cell.getWidth();
        int imgH = cell.getHeight();
        x = clamp(x, 0, imgW - 1);
        y = clamp(y, 0, imgH - 1);
        w = Math.min(w, imgW - x);
        h = Math.min(h, imgH - y);
        if (w <= 1 || h <= 1) {
            return new Rect(x, y, w, h);
        }

        int look = Math.min(Math.max(0, imgW - (x + w)), Math.max(6, Math.round(h * TRAIL_LOOK_H_RATIO)));
        int maxGlyphW = Math.max(8, Math.round(h * MAX_TRAIL_GLYPH_W_RATIO));
        int padY = Math.max(2, Math.round(h * 0.22f));
        int endX = x + w;
        int bestRight = endX - 1;
        int bestBottom = y + h - 1;

        if (look > 0) {
            int roiX0 = endX;
            int roiY0 = Math.max(0, y - padY);
            int roiX1 = endX + look;
            int roiY1 = Math.min(imgH, y + h + padY);
            List<int[]> comps = componentsInRoi(cell, roiX0, roiY0, roiX1, roiY1);
            int minYOverlap = Math.max(2, Math.round(h * 0.10f));
            for (int[] bb : comps) {
                int absMinX = roiX0 + bb[0];
                int absMaxX = roiX0 + bb[2];
                int absMinY = roiY0 + bb[1];
                int absMaxY = roiY0 + bb[3];
                int glyphW = absMaxX - absMinX + 1;
                int glyphH = absMaxY - absMinY + 1;
                if (glyphW <= 1 || glyphW > maxGlyphW) {
                    continue;
                }
                if (glyphH > Math.round(h * 0.60f)) {
                    continue;
                }
                if (absMinX > endX + ADJACENT_GAP_PX) {
                    continue;
                }
                int oy0 = Math.max(absMinY, y);
                int oy1 = Math.min(absMaxY + 1, y + h + padY);
                if (oy1 - oy0 < minYOverlap) {
                    continue;
                }
                bestRight = Math.max(bestRight, absMaxX);
                bestBottom = Math.max(bestBottom, absMaxY);
            }
        }

        // 框内下方：下标可能仍在 endX 左侧、却伸到框底外
        int underY0 = y + h;
        int underY1 = Math.min(imgH, y + h + Math.max(4, Math.round(h * 0.28f)));
        if (underY1 > underY0) {
            int underX1 = Math.min(imgW, endX + Math.max(0, look));
            List<int[]> under = componentsInRoi(cell, x, underY0, underX1, underY1);
            for (int[] bb : under) {
                int absMinX = x + bb[0];
                int absMaxX = x + bb[2];
                int absMaxY = underY0 + bb[3];
                int glyphW = absMaxX - absMinX + 1;
                if (glyphW > maxGlyphW) {
                    continue;
                }
                bestRight = Math.max(bestRight, absMaxX);
                bestBottom = Math.max(bestBottom, absMaxY);
            }
        }

        int nw = Math.min(imgW - x, Math.max(w, bestRight - x + 1));
        int nh = Math.min(imgH - y, Math.max(h, bestBottom - y + 1));
        return new Rect(x, y, nw, nh);
    }

    static boolean leftInteriorOwnsWideGlyph(BufferedImage cell, int x, int y, int w, int h) {
        int stripW = Math.max(4, Math.round(w * OWN_LEFT_STRIP_W_RATIO));
        stripW = Math.min(stripW, w);
        int minOwnW = Math.max(3, Math.round(h * OWN_LEFT_W_RATIO));
        int minOwnH = Math.max(3, Math.round(h * OWN_LEFT_H_RATIO));
        int dark = 0;
        int area = stripW * h;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + stripW; xx++) {
                if (isDark(cell.getRGB(xx, yy))) {
                    dark++;
                    minY = Math.min(minY, yy);
                    maxY = Math.max(maxY, yy);
                    minX = Math.min(minX, xx);
                    maxX = Math.max(maxX, xx);
                }
            }
        }
        if (area > 0 && dark >= Math.max(6, Math.round(area * OWN_LEFT_DARK_RATIO))
                && maxY >= minY && (maxY - minY + 1) >= minOwnH
                && maxX >= minX && (maxX - minX + 1) >= minOwnW) {
            return true;
        }
        List<int[]> comps = componentsInRoi(cell, x, y, x + stripW, y + h);
        for (int[] bb : comps) {
            int gw = bb[2] - bb[0] + 1;
            int gh = bb[3] - bb[1] + 1;
            if (gw >= minOwnW && gh >= minOwnH) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> componentsInRoi(
            BufferedImage cell, int roiX0, int roiY0, int roiX1, int roiY1) {
        int rw = roiX1 - roiX0;
        int rh = roiY1 - roiY0;
        List<int[]> comps = new ArrayList<>();
        if (rw <= 0 || rh <= 0) {
            return comps;
        }
        boolean[] dark = new boolean[rw * rh];
        for (int yy = roiY0; yy < roiY1; yy++) {
            for (int xx = roiX0; xx < roiX1; xx++) {
                if (xx >= 0 && yy >= 0 && xx < cell.getWidth() && yy < cell.getHeight()
                        && isDark(cell.getRGB(xx, yy))) {
                    dark[(yy - roiY0) * rw + (xx - roiX0)] = true;
                }
            }
        }
        boolean[] visited = new boolean[dark.length];
        for (int i = 0; i < dark.length; i++) {
            if (!dark[i] || visited[i]) {
                continue;
            }
            int[] bb = flood(dark, visited, rw, rh, i);
            if (bb != null) {
                comps.add(bb);
            }
        }
        return comps;
    }

    private static int[] flood(boolean[] dark, boolean[] visited, int rw, int rh, int start) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(start);
        visited[start] = true;
        int minX = start % rw;
        int maxX = minX;
        int minY = start / rw;
        int maxY = minY;
        int count = 0;
        while (!q.isEmpty()) {
            int i = q.removeFirst();
            count++;
            int cx = i % rw;
            int cy = i / rw;
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minY = Math.min(minY, cy);
            maxY = Math.max(maxY, cy);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) {
                        continue;
                    }
                    int j = ny * rw + nx;
                    if (!dark[j] || visited[j]) {
                        continue;
                    }
                    visited[j] = true;
                    q.add(j);
                }
            }
        }
        if (count < 4) {
            return null;
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    private static boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (r + g + b) < DARK_THRESH * 3;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
