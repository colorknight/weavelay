package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.page.PageClassifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表格列: 由列头 OCR 框推断列宽, 在首行数据带内取值; 无 OCR 时仍返回空字符串.
 */
public final class TableColumnValueCollector {

    private static final double MIN_DATA_GAP = 8.0;
    private static final double MIN_VERTICAL_OVERLAP_PX = 12.0;
    private static final double MIN_OVERLAP_RATIO = 0.35;
    private static final double COLUMN_EDGE_MARGIN = 6.0;

    private TableColumnValueCollector() {
    }

    public static Map<String, PairMatch> collectColumns(
            List<OcrRow> pageRows,
            List<String> columnLabels,
            double rowTolerance) {
        Map<String, PairMatch> out = new LinkedHashMap<String, PairMatch>();
        if (pageRows == null || columnLabels == null || columnLabels.isEmpty()) {
            return out;
        }
        for (String label : columnLabels) {
            out.put(label, PairMatch.empty());
        }

        List<HeaderCell> headers = findHeaderCells(pageRows, columnLabels);
        if (headers.isEmpty()) {
            return out;
        }

        HeaderBand band = pickBestBand(headers);
        if (band.headers.isEmpty()) {
            return out;
        }

        for (int i = 0; i < band.headers.size(); i++) {
            HeaderCell header = band.headers.get(i);
            double left = columnLeft(band.headers, i);
            double right = columnRight(band.headers, i);
            double dataTop = band.maxEndY() + MIN_DATA_GAP;
            double dataBottom = dataTop + estimateDataRowHeight(pageRows, band, rowTolerance);
            List<OcrRow> values = collectInColumn(pageRows, left, right, dataTop, dataBottom);
            values = sortTopToBottom(values);
            String text = values.stream()
                    .map(OcrRow::getFeature)
                    .filter(f -> f != null && !f.trim().isEmpty())
                    .map(String::trim)
                    .collect(Collectors.joining("\n"));
            OcrRow cellRegion = TableColumnCellRegion.create(
                    header.row.getStreamName(), left, dataTop, right, dataBottom);
            OcrRow valueRow = values.isEmpty() ? cellRegion : values.get(0);
            out.put(header.label, PairMatch.of(header.row, valueRow, text));
        }
        return out;
    }

    private static List<HeaderCell> findHeaderCells(List<OcrRow> pageRows, List<String> columnLabels) {
        List<HeaderCell> headers = new ArrayList<HeaderCell>();
        for (String label : columnLabels) {
            String norm = PageClassifier.normalize(label);
            for (OcrRow row : pageRows) {
                if (row == null || row.getFeature() == null) {
                    continue;
                }
                if (PageClassifier.normalize(row.getFeature()).equals(norm)) {
                    headers.add(new HeaderCell(label, row));
                }
            }
        }
        return headers;
    }

    private static HeaderBand pickBestBand(List<HeaderCell> headers) {
        List<HeaderBand> bands = new ArrayList<HeaderBand>();
        for (HeaderCell header : headers) {
            HeaderBand target = null;
            for (HeaderBand band : bands) {
                if (band.overlapsBand(header.row)) {
                    target = band;
                    break;
                }
            }
            if (target == null) {
                target = new HeaderBand();
                bands.add(target);
            }
            target.add(header);
        }
        HeaderBand best = new HeaderBand();
        int bestCount = -1;
        for (HeaderBand band : bands) {
            band.sortByX();
            if (band.headers.size() > bestCount) {
                bestCount = band.headers.size();
                best = band;
            }
        }
        return best;
    }

    private static double columnLeft(List<HeaderCell> headers, int index) {
        HeaderCell current = headers.get(index);
        if (index == 0) {
            return current.row.getStartX() - COLUMN_EDGE_MARGIN;
        }
        HeaderCell prev = headers.get(index - 1);
        return (prev.row.getEndX() + current.row.getStartX()) / 2.0;
    }

    private static double columnRight(List<HeaderCell> headers, int index) {
        HeaderCell current = headers.get(index);
        if (index == headers.size() - 1) {
            return current.row.getEndX() + COLUMN_EDGE_MARGIN;
        }
        HeaderCell next = headers.get(index + 1);
        return (current.row.getEndX() + next.row.getStartX()) / 2.0;
    }

    private static List<OcrRow> collectInColumn(
            List<OcrRow> pageRows,
            double left,
            double right,
            double dataTop,
            double dataBottom) {
        List<OcrRow> values = new ArrayList<OcrRow>();
        for (OcrRow row : pageRows) {
            if (row == null || !row.hasBbox()) {
                continue;
            }
            double cx = row.centerX();
            double cy = row.centerY();
            if (cx < left || cx > right) {
                continue;
            }
            if (cy < dataTop || cy > dataBottom) {
                continue;
            }
            values.add(row);
        }
        return values;
    }

    /** 数据带高度: 取表头下方所有 OCR 行中最低的 bottom 与 dataTop 的差值, 兜底 200px. */
    private static double estimateDataRowHeight(List<OcrRow> pageRows, HeaderBand band,
                                                 double rowTolerance) {
        double dataTop = band.maxEndY() + MIN_DATA_GAP;
        double maxBottom = dataTop + 200;
        for (OcrRow row : pageRows) {
            if (row == null || !row.hasBbox()) continue;
            if (row.getEndY() > maxBottom) maxBottom = row.getEndY();
        }
        return Math.max(rowTolerance, maxBottom - dataTop);
    }

    private static boolean shareVerticalBand(OcrRow a, OcrRow b) {
        double overlap = verticalOverlap(a, b);
        if (overlap >= MIN_VERTICAL_OVERLAP_PX) {
            return true;
        }
        double minHeight = Math.min(boxHeight(a), boxHeight(b));
        return minHeight > 0 && overlap / minHeight >= MIN_OVERLAP_RATIO;
    }

    private static double verticalOverlap(OcrRow a, OcrRow b) {
        double top = Math.max(a.getStartY(), b.getStartY());
        double bottom = Math.min(a.getEndY(), b.getEndY());
        return Math.max(0, bottom - top);
    }

    private static double boxHeight(OcrRow row) {
        return Math.max(0, row.getEndY() - row.getStartY());
    }

    static List<OcrRow> sortTopToBottom(List<OcrRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<OcrRow> sorted = new ArrayList<OcrRow>(rows);
        sorted.sort(Comparator
                .comparingDouble(OcrRow::getStartY)
                .thenComparingDouble(OcrRow::getStartX));
        return sorted;
    }

    private static final class HeaderCell {
        private final String label;
        private final OcrRow row;

        private HeaderCell(String label, OcrRow row) {
            this.label = label;
            this.row = row;
        }
    }

    private static final class HeaderBand {
        private final List<HeaderCell> headers = new ArrayList<HeaderCell>();

        private void add(HeaderCell header) {
            for (HeaderCell existing : headers) {
                if (existing.label.equals(header.label)) {
                    return;
                }
            }
            headers.add(header);
        }

        private boolean overlapsBand(OcrRow row) {
            for (HeaderCell header : headers) {
                if (shareVerticalBand(header.row, row)) {
                    return true;
                }
            }
            return false;
        }

        private void sortByX() {
            headers.sort(Comparator.comparingDouble(h -> h.row.getStartX()));
        }

        private double minStartY() {
            return headers.stream().mapToDouble(h -> h.row.getStartY()).min().orElse(0);
        }

        private double maxEndY() {
            return headers.stream().mapToDouble(h -> h.row.getEndY()).max().orElse(0);
        }
    }
}
