package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.page.PageClassifier;

import java.util.List;

/**
 * 标签-值坐标配对 (横向 / 纵向).
 */
public final class LabelValuePairer {

    private LabelValuePairer() {
    }

    public static PairMatch pairHorizontal(List<OcrRow> pageRows, String label, double rowTolerance) {
        String labelNorm = PageClassifier.normalize(label);
        OcrRow labelRow = findExactLabel(pageRows, labelNorm);
        if (labelRow == null) {
            return PairMatch.empty();
        }

        double labelEndX = labelRow.getEndX();
        double labelCy = labelRow.centerY();
        OcrRow best = null;
        Double bestScore = null;

        for (OcrRow row : pageRows) {
            if (row == labelRow) {
                continue;
            }
            String feat = row.getFeature();
            if (feat == null || feat.trim().isEmpty()) {
                continue;
            }
            if (PageClassifier.normalize(feat).equals(labelNorm)) {
                continue;
            }
            double dy = Math.abs(row.centerY() - labelCy);
            if (dy > rowTolerance) {
                continue;
            }
            double gap = row.getStartX() - labelEndX;
            if (gap < 20) {
                continue;
            }
            double score = dy * 10 + gap;
            if (bestScore == null || score < bestScore) {
                bestScore = score;
                best = row;
            }
        }
        if (best == null) {
            return PairMatch.of(labelRow, null, "");
        }
        return PairMatch.of(labelRow, best, best.getFeature());
    }

    public static PairMatch pairVertical(List<OcrRow> pageRows, String label, double colTolerance) {
        String labelNorm = PageClassifier.normalize(label);
        OcrRow labelRow = findExactLabel(pageRows, labelNorm);
        if (labelRow == null) {
            return PairMatch.empty();
        }

        double labelCx = labelRow.centerX();
        double labelBottom = labelRow.getEndY();
        OcrRow best = null;
        Double bestScore = null;

        for (OcrRow row : pageRows) {
            if (row == labelRow) {
                continue;
            }
            String feat = row.getFeature();
            if (feat == null || feat.trim().isEmpty()) {
                continue;
            }
            if (PageClassifier.normalize(feat).equals(labelNorm)) {
                continue;
            }
            double dx = Math.abs(row.centerX() - labelCx);
            if (dx > colTolerance) {
                continue;
            }
            double gap = row.getStartY() - labelBottom;
            if (gap < 0) {
                continue;
            }
            double score = dx * 10 + gap;
            if (bestScore == null || score < bestScore) {
                bestScore = score;
                best = row;
            }
        }
        if (best == null) {
            return PairMatch.of(labelRow, null, "");
        }
        return PairMatch.of(labelRow, best, best.getFeature());
    }

    private static OcrRow findExactLabel(List<OcrRow> pageRows, String labelNorm) {
        for (OcrRow row : pageRows) {
            if (PageClassifier.normalize(row.getFeature()).equals(labelNorm)) {
                return row;
            }
        }
        return null;
    }
}
