package com.weavelay.core.merge;

import com.weavelay.core.model.MergeParams;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.WeaveRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 同行聚类 + 横向 merge + 输出 [streamName, feature, seq].
 */
public final class RowMerger {

    private static final List<OcrRow> EMPTY_OCR_ROWS = Collections.emptyList();

    private RowMerger() {
    }

    public static List<WeaveRow> mergeByPage(List<OcrRow> rows, MergeParams params) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<OcrRow>> pages = new TreeMap<String, List<OcrRow>>();
        List<OcrRow> noBbox = new ArrayList<OcrRow>();
        for (OcrRow row : rows) {
            if (row == null) {
                continue;
            }
            if (!row.hasBbox()) {
                noBbox.add(row);
                continue;
            }
            if (!pages.containsKey(row.getStreamName())) {
                pages.put(row.getStreamName(), new ArrayList<OcrRow>());
            }
            pages.get(row.getStreamName()).add(row);
        }

        List<OcrRow> mergedFull = new ArrayList<OcrRow>();
        for (List<OcrRow> pageRows : pages.values()) {
            mergedFull.addAll(mergePage(pageRows, params));
        }
        return formatOutput(mergedFull, noBbox, params.getYThreshold());
    }

    public static List<WeaveRow> merge(List<OcrRow> rows, MergeParams params) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrRow> merged = mergePage(rows, params);
        return formatOutput(merged, EMPTY_OCR_ROWS, params.getYThreshold());
    }

    /**
     * 与 {@link #merge} 相同顺序的 merge 后 OCR 行, 保留检测框置信度 {@link OcrRow#getProb()}.
     */
    public static List<OcrRow> mergeOcrLines(List<OcrRow> rows, MergeParams params) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrRow> merged = mergePage(rows, params);
        return flattenMergedPage(merged, params.getYThreshold());
    }

    private static List<OcrRow> flattenMergedPage(List<OcrRow> mergedRows, double yThreshold) {
        List<OcrRow> out = new ArrayList<OcrRow>();
        for (List<OcrRow> line : groupIntoLines(mergedRows, yThreshold)) {
            out.addAll(line);
        }
        return out;
    }

    public static List<WeaveRow> process(List<OcrRow> rows, MergeParams params) {
        if (params.isByPage()) {
            return mergeByPage(rows, params);
        }
        return merge(rows, params);
    }

    public static List<OcrRow> normalize(List<?> rawRows) {
        List<OcrRow> out = new ArrayList<OcrRow>();
        if (rawRows == null) {
            return out;
        }
        for (Object raw : rawRows) {
            if (!(raw instanceof List)) {
                continue;
            }
            OcrRow row = OcrRow.fromList((List<?>) raw);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    private static List<OcrRow> mergePage(List<OcrRow> rows, MergeParams params) {
        List<OcrRow> result = new ArrayList<OcrRow>();
        for (List<OcrRow> line : groupIntoLines(rows, params.getYThreshold())) {
            result.addAll(mergeLine(line, params.getXThreshold(), params.getMergeSep()));
        }
        return result;
    }

    /**
     * 阅读顺序: 按检测框 Y 区间重叠分行 (不 merge 文本), 行按 top→bottom, 行内 left→right.
     */
    public static List<OcrRow> sortForReading(List<OcrRow> rows) {
        return sortForReading(rows, 12.0, 0.35);
    }

    public static List<OcrRow> sortForReading(List<OcrRow> rows, double minVerticalOverlapPx) {
        return sortForReading(rows, minVerticalOverlapPx, 0.35);
    }

    public static List<OcrRow> sortForReading(
            List<OcrRow> rows,
            double minVerticalOverlapPx,
            double minOverlapRatio) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrRow> withBbox = new ArrayList<OcrRow>();
        List<OcrRow> noBbox = new ArrayList<OcrRow>();
        for (OcrRow row : rows) {
            if (row == null) {
                continue;
            }
            if (row.hasBbox()) {
                withBbox.add(row);
            } else {
                noBbox.add(row);
            }
        }
        List<List<OcrRow>> lines = groupIntoReadingLines(withBbox, minVerticalOverlapPx, minOverlapRatio);
        lines.sort(Comparator
                .comparingDouble(RowMerger::lineTopY)
                .thenComparingDouble(RowMerger::lineLeftX));
        List<OcrRow> ordered = new ArrayList<OcrRow>();
        for (List<OcrRow> line : lines) {
            sortLineForReading(line);
            ordered.addAll(line);
        }
        ordered.addAll(noBbox);
        return fixColumnStackOrder(ordered);
    }

    /**
     * 同一阅读行内: 横向排布按 X; 同列竖排 (材料规格块等) 按 Y 从上到下.
     */
    static void sortLineForReading(List<OcrRow> line) {
        if (line.size() <= 1) {
            return;
        }
        if (isVerticalStack(line)) {
            line.sort(Comparator
                    .comparingDouble(OcrRow::getStartY)
                    .thenComparingDouble(OcrRow::getStartX));
        } else {
            line.sort(Comparator.comparingDouble(OcrRow::getStartX));
        }
    }

    static boolean isVerticalStack(List<OcrRow> line) {
        if (line.size() <= 1) {
            return false;
        }
        double minStartX = line.stream().mapToDouble(OcrRow::getStartX).min().orElse(0);
        double maxEndX = line.stream().mapToDouble(OcrRow::getEndX).max().orElse(0);
        if (maxEndX - minStartX > 80) {
            return false;
        }
        double minY = line.stream().mapToDouble(OcrRow::getStartY).min().orElse(0);
        double maxY = line.stream().mapToDouble(OcrRow::getEndY).max().orElse(0);
        double avgHeight = line.stream().mapToDouble(RowMerger::boxHeight).average().orElse(1);
        return maxY - minY > avgHeight * 1.2;
    }

    /**
     * 阅读序已定时, 同列连续竖排块再按 startY 校正 (避免材料规格两行颠倒).
     */
    static List<OcrRow> fixColumnStackOrder(List<OcrRow> ordered) {
        if (ordered == null || ordered.size() <= 1) {
            return ordered == null ? Collections.emptyList() : ordered;
        }
        List<OcrRow> result = new ArrayList<OcrRow>(ordered);
        int index = 0;
        while (index < result.size()) {
            int end = index + 1;
            while (end < result.size() && sameColumn(result.get(index), result.get(end))) {
                end++;
            }
            if (end - index > 1) {
                List<OcrRow> stack = new ArrayList<OcrRow>(result.subList(index, end));
                stack.sort(Comparator
                        .comparingDouble(OcrRow::getStartY)
                        .thenComparingDouble(OcrRow::getStartX));
                for (int i = index; i < end; i++) {
                    result.set(i, stack.get(i - index));
                }
            }
            index = end;
        }
        return result;
    }

    private static boolean sameColumn(OcrRow a, OcrRow b) {
        return Math.abs(a.centerX() - b.centerX()) <= 45;
    }

    static List<List<OcrRow>> groupIntoReadingLines(
            List<OcrRow> rows,
            double minVerticalOverlapPx,
            double minOverlapRatio) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrRow> sorted = rows.stream()
                .sorted(Comparator.comparingDouble(OcrRow::getStartY)
                        .thenComparingDouble(OcrRow::getStartX))
                .collect(Collectors.toList());

        List<List<OcrRow>> lines = new ArrayList<List<OcrRow>>();
        for (OcrRow row : sorted) {
            List<OcrRow> targetLine = null;
            for (List<OcrRow> line : lines) {
                for (OcrRow member : line) {
                    if (shareVerticalBand(row, member, minVerticalOverlapPx, minOverlapRatio)) {
                        targetLine = line;
                        break;
                    }
                }
                if (targetLine != null) {
                    break;
                }
            }
            if (targetLine == null) {
                targetLine = new ArrayList<OcrRow>();
                lines.add(targetLine);
            }
            targetLine.add(row);
        }
        for (List<OcrRow> line : lines) {
            sortLineForReading(line);
        }
        return lines;
    }

    private static boolean shareVerticalBand(
            OcrRow a,
            OcrRow b,
            double minVerticalOverlapPx,
            double minOverlapRatio) {
        double overlap = verticalOverlap(a, b);
        if (overlap >= minVerticalOverlapPx) {
            return true;
        }
        double minHeight = Math.min(boxHeight(a), boxHeight(b));
        return minHeight > 0 && overlap / minHeight >= minOverlapRatio;
    }

    private static double verticalOverlap(OcrRow a, OcrRow b) {
        double top = Math.max(a.getStartY(), b.getStartY());
        double bottom = Math.min(a.getEndY(), b.getEndY());
        return Math.max(0, bottom - top);
    }

    private static double boxHeight(OcrRow row) {
        return Math.max(0, row.getEndY() - row.getStartY());
    }

    private static double lineTopY(List<OcrRow> line) {
        return line.stream().mapToDouble(OcrRow::getStartY).min().orElse(0);
    }

    private static double lineLeftX(List<OcrRow> line) {
        return line.stream().mapToDouble(OcrRow::getStartX).min().orElse(0);
    }

    static List<List<OcrRow>> groupIntoLines(List<OcrRow> rows, double yThreshold) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<OcrRow> sorted = rows.stream()
                .sorted(Comparator.comparingDouble(OcrRow::centerY)
                        .thenComparingDouble(OcrRow::getStartX))
                .collect(Collectors.toList());

        List<List<OcrRow>> lines = new ArrayList<List<OcrRow>>();
        List<OcrRow> current = new ArrayList<OcrRow>();
        current.add(sorted.get(0));
        double lineY = sorted.get(0).centerY();

        for (int i = 1; i < sorted.size(); i++) {
            OcrRow row = sorted.get(i);
            if (Math.abs(row.centerY() - lineY) <= yThreshold) {
                current.add(row);
            } else {
                Collections.sort(current, Comparator.comparingDouble(OcrRow::getStartX));
                lines.add(current);
                current = new ArrayList<OcrRow>();
                current.add(row);
                lineY = row.centerY();
            }
        }
        Collections.sort(current, Comparator.comparingDouble(OcrRow::getStartX));
        lines.add(current);
        return lines;
    }

    static List<OcrRow> mergeLine(List<OcrRow> line, double xThreshold, String sep) {
        if (line.isEmpty()) {
            return Collections.emptyList();
        }
        if (line.size() == 1) {
            return Collections.singletonList(line.get(0).copy());
        }
        List<OcrRow> merged = new ArrayList<OcrRow>();
        merged.add(line.get(0).copy());
        for (int i = 1; i < line.size(); i++) {
            OcrRow next = line.get(i);
            OcrRow prev = merged.get(merged.size() - 1);
            double gap = next.getStartX() - prev.getEndX();
            if (gap <= xThreshold) {
                merged.set(merged.size() - 1, mergeTwo(prev, next, sep));
            } else {
                merged.add(next.copy());
            }
        }
        return merged;
    }

    private static OcrRow mergeTwo(OcrRow a, OcrRow b, String sep) {
        OcrRow out = a.copy();
        String left = a.getFeature() == null ? "" : a.getFeature().trim();
        String right = b.getFeature() == null ? "" : b.getFeature().trim();
        if (!left.isEmpty() && !right.isEmpty()) {
            out.setFeature((left + sep + right).trim());
        } else {
            out.setFeature(left.isEmpty() ? right : left);
        }
        out.setProb(Math.min(a.getProb(), b.getProb()));
        out.setStartX(Math.min(a.getStartX(), b.getStartX()));
        out.setStartY(Math.min(a.getStartY(), b.getStartY()));
        out.setEndX(Math.max(a.getEndX(), b.getEndX()));
        out.setEndY(Math.max(a.getEndY(), b.getEndY()));
        return out;
    }

    private static List<WeaveRow> formatOutput(
            List<OcrRow> mergedRows,
            List<OcrRow> passthroughRows,
            double yThreshold) {
        Map<String, List<OcrRow>> pages = new LinkedHashMap<String, List<OcrRow>>();
        for (OcrRow row : mergedRows) {
            if (!pages.containsKey(row.getStreamName())) {
                pages.put(row.getStreamName(), new ArrayList<OcrRow>());
            }
            pages.get(row.getStreamName()).add(row);
        }
        Map<String, List<OcrRow>> passthroughPages = new LinkedHashMap<String, List<OcrRow>>();
        for (OcrRow row : passthroughRows) {
            if (!passthroughPages.containsKey(row.getStreamName())) {
                passthroughPages.put(row.getStreamName(), new ArrayList<OcrRow>());
            }
            passthroughPages.get(row.getStreamName()).add(row);
        }

        List<String> keys = new ArrayList<String>(pages.keySet());
        for (String key : passthroughPages.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        Collections.sort(keys);

        List<WeaveRow> out = new ArrayList<WeaveRow>();
        for (String key : keys) {
            int seq = 1;
            List<OcrRow> pageRows = pages.containsKey(key) ? pages.get(key) : EMPTY_OCR_ROWS;
            for (List<OcrRow> line : groupIntoLines(pageRows, yThreshold)) {
                for (OcrRow row : line) {
                    out.add(new WeaveRow(key, row.getFeature().trim(), seq++));
                }
            }
            List<OcrRow> passRows = passthroughPages.containsKey(key)
                    ? passthroughPages.get(key) : EMPTY_OCR_ROWS;
            for (OcrRow row : passRows) {
                out.add(new WeaveRow(key, row.getFeature().trim(), seq++));
            }
        }
        return out;
    }
}
