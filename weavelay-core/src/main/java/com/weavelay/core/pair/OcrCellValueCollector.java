package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.page.PageClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表格单元格: 标签在左、多行值在右时, 按坐标区域收集并合并为换行文本.
 */
public final class OcrCellValueCollector {

    private static final double MIN_HORIZONTAL_GAP = 20.0;
    private static final double MIN_VERTICAL_OVERLAP_PX = 12.0;
    private static final double MIN_OVERLAP_RATIO = 0.35;

    private OcrCellValueCollector() {
    }

    public static PairMatch collectBesideLabel(
            List<OcrRow> pageRows,
            String label,
            double rowTolerance,
            double maxCellWidth) {
        if (pageRows == null || pageRows.isEmpty() || label == null || label.trim().isEmpty()) {
            return PairMatch.empty();
        }
        String labelNorm = PageClassifier.normalize(label);
        OcrRow labelRow = findLabelWithMostBesideValues(pageRows, labelNorm, rowTolerance);
        if (labelRow == null) {
            return PairMatch.empty();
        }

        List<OcrRow> seeds = findBesideSeeds(pageRows, labelRow, labelNorm, rowTolerance);
        if (seeds.isEmpty()) {
            return PairMatch.of(labelRow, null, "");
        }

        double minY = labelRow.getStartY();
        double maxY = labelRow.getEndY();
        double minX = labelRow.getEndX() + MIN_HORIZONTAL_GAP;
        double maxX = labelRow.getEndX() + Math.max(maxCellWidth, MIN_HORIZONTAL_GAP);
        for (OcrRow seed : seeds) {
            minY = Math.min(minY, seed.getStartY());
            maxY = Math.max(maxY, seed.getEndY());
            maxX = Math.max(maxX, seed.getEndX() + rowTolerance * 0.25);
        }
        minY -= rowTolerance * 0.25;
        maxY += rowTolerance * 0.25;

        List<OcrRow> inCell = collectInRegion(pageRows, labelRow, labelNorm, minX, maxX, minY, maxY);
        inCell = OcrBoxDeduper.dropStrictlyContained(inCell);
        inCell = TableColumnValueCollector.sortTopToBottom(inCell);
        if (inCell.isEmpty()) {
            return PairMatch.of(labelRow, null, "");
        }

        String value = inCell.stream()
                .map(OcrRow::getFeature)
                .filter(f -> f != null && !f.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.joining("\n"));
        return PairMatch.of(labelRow, inCell.get(0), value);
    }

    private static OcrRow findLabelWithMostBesideValues(
            List<OcrRow> pageRows,
            String labelNorm,
            double rowTolerance) {
        OcrRow best = null;
        int bestCount = -1;
        for (OcrRow row : pageRows) {
            if (!PageClassifier.normalize(row.getFeature()).equals(labelNorm)) {
                continue;
            }
            int count = findBesideSeeds(pageRows, row, labelNorm, rowTolerance).size();
            if (count > bestCount) {
                bestCount = count;
                best = row;
            }
        }
        return best;
    }

    private static List<OcrRow> findBesideSeeds(
            List<OcrRow> pageRows,
            OcrRow labelRow,
            String labelNorm,
            double rowTolerance) {
        List<OcrRow> seeds = new ArrayList<OcrRow>();
        for (OcrRow row : pageRows) {
            if (row == labelRow || isBlank(row) || isSameLabel(row, labelNorm)) {
                continue;
            }
            if (row.getStartX() < labelRow.getEndX() + MIN_HORIZONTAL_GAP) {
                continue;
            }
            if (!shareVerticalBand(row, labelRow, rowTolerance)) {
                continue;
            }
            seeds.add(row);
        }
        return seeds;
    }

    private static List<OcrRow> collectInRegion(
            List<OcrRow> pageRows,
            OcrRow labelRow,
            String labelNorm,
            double minX,
            double maxX,
            double minY,
            double maxY) {
        List<OcrRow> inCell = new ArrayList<OcrRow>();
        for (OcrRow row : pageRows) {
            if (row == labelRow || isBlank(row) || isSameLabel(row, labelNorm)) {
                continue;
            }
            if (row.getStartX() < minX) {
                continue;
            }
            if (row.getEndX() > maxX) {
                continue;
            }
            double cy = row.centerY();
            if (cy < minY || cy > maxY) {
                continue;
            }
            inCell.add(row);
        }
        return inCell;
    }

    private static boolean shareVerticalBand(OcrRow a, OcrRow b, double rowTolerance) {
        double overlap = verticalOverlap(a, b);
        if (overlap >= MIN_VERTICAL_OVERLAP_PX) {
            return true;
        }
        double minHeight = Math.min(boxHeight(a), boxHeight(b));
        if (minHeight > 0 && overlap / minHeight >= MIN_OVERLAP_RATIO) {
            return true;
        }
        return Math.abs(a.centerY() - b.centerY()) <= rowTolerance;
    }

    private static double verticalOverlap(OcrRow a, OcrRow b) {
        double top = Math.max(a.getStartY(), b.getStartY());
        double bottom = Math.min(a.getEndY(), b.getEndY());
        return Math.max(0, bottom - top);
    }

    private static double boxHeight(OcrRow row) {
        return Math.max(0, row.getEndY() - row.getStartY());
    }

    private static boolean isBlank(OcrRow row) {
        return row.getFeature() == null || row.getFeature().trim().isEmpty();
    }

    private static boolean isSameLabel(OcrRow row, String labelNorm) {
        return PageClassifier.normalize(row.getFeature()).equals(labelNorm);
    }
}
