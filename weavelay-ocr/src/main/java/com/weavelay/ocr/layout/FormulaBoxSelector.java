package com.weavelay.ocr.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从表单元格版式结果里筛公式框：降阈值捞多段 inline，再 NMS 去掉大包小/重复框。
 */
public final class FormulaBoxSelector {

    /** 单元格内公式检测阈值（全页默认 0.5 会漏第二段 ⌀134 / ⌀11）。 */
    public static final float CELL_FORMULA_SCORE_MIN = 0.17f;
    /** 宽超过图宽此比例视为“整行假公式”，丢掉。 */
    private static final double MAX_WIDTH_RATIO = 0.48;
    private static final double NMS_IOU = 0.4;

    private FormulaBoxSelector() {}

    public static List<PpDocLayoutBox> select(List<PpDocLayoutBox> all, int imgW, int imgH) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int wLimit = Math.max(8, imgW);
        List<PpDocLayoutBox> cands = new ArrayList<>();
        for (PpDocLayoutBox b : all) {
            if (b == null || !b.isFormula()) {
                continue;
            }
            if (b.getScore() < CELL_FORMULA_SCORE_MIN) {
                continue;
            }
            if (b.getWidth() <= 1 || b.getHeight() <= 1) {
                continue;
            }
            if (b.getWidth() > wLimit * MAX_WIDTH_RATIO) {
                continue;
            }
            cands.add(b);
        }
        cands.sort(Comparator.comparingDouble(PpDocLayoutBox::getScore).reversed());

        List<PpDocLayoutBox> kept = new ArrayList<>();
        for (PpDocLayoutBox cand : cands) {
            boolean overlap = false;
            for (PpDocLayoutBox k : kept) {
                if (iou(cand, k) >= NMS_IOU || contains(cand, k) || contains(k, cand)) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                kept.add(cand);
            }
        }
        kept.sort(Comparator
                .comparingDouble(PpDocLayoutBox::getYmin)
                .thenComparingDouble(PpDocLayoutBox::getXmin));
        return kept;
    }

    private static boolean contains(PpDocLayoutBox outer, PpDocLayoutBox inner) {
        return outer.getXmin() <= inner.getXmin() + 1
                && outer.getYmin() <= inner.getYmin() + 1
                && outer.getXmax() >= inner.getXmax() - 1
                && outer.getYmax() >= inner.getYmax() - 1
                && (outer.getWidth() * outer.getHeight() > inner.getWidth() * inner.getHeight() * 1.15);
    }

    private static double iou(PpDocLayoutBox a, PpDocLayoutBox b) {
        double ox = Math.max(0, Math.min(a.getXmax(), b.getXmax()) - Math.max(a.getXmin(), b.getXmin()));
        double oy = Math.max(0, Math.min(a.getYmax(), b.getYmax()) - Math.max(a.getYmin(), b.getYmin()));
        double inter = ox * oy;
        if (inter <= 0) {
            return 0;
        }
        double aa = Math.max(1.0, a.getWidth() * a.getHeight());
        double bb = Math.max(1.0, b.getWidth() * b.getHeight());
        return inter / (aa + bb - inter);
    }
}
