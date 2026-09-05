package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 表格识别：根据 OCR 行和列头定义，将矩形区域内的文字组装为行列结构。
 *
 * <p>支持列头在顶部（常规）和列头在左侧（转置）两种模式。</p>
 *
 * <p>识别策略：
 * <ol>
 *   <li>按 Y 坐标排序 OCR 行 → 从上到下分组为行</li>
 *   <li>每行内按 X 坐标排序</li>
 *   <li>如果列头在左侧：表格转置（第一列是字段名，后续列是值）</li>
 *   <li>空单元格自动留空</li>
 * </ol>
 */
public final class TableRecognizer {

    private TableRecognizer() {
    }

    /** 表格识别结果：每行是一个字符串数组，第一行是列头。 */
    public static final class TableResult {
        public final List<String[]> rows;       // 每行 [col0, col1, ...]
        public final String[] columnHeaders;    // 列头
        public final int rowCount;
        public final int colCount;

        TableResult(List<String[]> rows, String[] headers) {
            this.rows = rows;
            this.columnHeaders = headers;
            this.rowCount = rows.size();
            this.colCount = headers.length;
        }
    }

    /**
     * 识别表格。
     *
     * @param ocrRows       区域内的 OCR 行
     * @param columnHeaders 列头名数组（如 ["工序号","工序名称"]），null 则自动检测
     * @param headerOnLeft  true=列头在左侧（纵向表），false=列头在顶部（常规）
     */
    public static TableResult recognize(
            List<OcrRow> ocrRows,
            String[] columnHeaders,
            boolean headerOnLeft) {

        if (ocrRows == null || ocrRows.isEmpty()) {
            return new TableResult(Collections.emptyList(),
                    columnHeaders != null ? columnHeaders : new String[0]);
        }

        // 按 Y 排序，Y 相近的归为一行
        List<List<OcrRow>> rowGroups = groupByY(ocrRows);

        if (headerOnLeft) {
            return recognizeTransposed(rowGroups, columnHeaders);
        }
        return recognizeNormal(rowGroups, columnHeaders);
    }

    /** 常规表格：列头在顶部。 */
    private static TableResult recognizeNormal(
            List<List<OcrRow>> rowGroups, String[] columnHeaders) {

        if (rowGroups.isEmpty()) {
            return new TableResult(Collections.emptyList(),
                    columnHeaders != null ? columnHeaders : new String[0]);
        }

        // 如果没指定列头，用第一行检测
        String[] headers = columnHeaders;
        int dataStartRow = 0;

        if (headers == null || headers.length == 0) {
            // 自动检测：第一行为列头
            if (rowGroups.size() >= 1) {
                List<OcrRow> headerRow = rowGroups.get(0);
                headers = new String[headerRow.size()];
                for (int i = 0; i < headerRow.size(); i++) {
                    headers[i] = headerRow.get(i).getFeature().trim();
                }
                dataStartRow = 1;
            } else {
                headers = new String[0];
            }
        }

        // 构建数据行：每个 Y 组是一行，按 X 分配到对应列
        List<String[]> rows = new ArrayList<>();
        for (int gi = dataStartRow; gi < rowGroups.size(); gi++) {
            List<OcrRow> group = rowGroups.get(gi);
            String[] row = new String[headers.length];
            for (int ci = 0; ci < headers.length; ci++) {
                row[ci] = ""; // 默认为空
            }
            // 把 group 中各 OCR 行按 X 分配到最接近的列
            for (OcrRow ocr : group) {
                int col = findNearestColumn(ocr.centerX(), rowGroups.get(dataStartRow > 0 ? 0 : 0));
                if (col >= 0 && col < row.length) {
                    String val = ocr.getFeature().trim();
                    row[col] = row[col].isEmpty() ? val : row[col] + " " + val;
                }
            }
            rows.add(row);
        }

        // 合并连续的单列多行：如果连续 N 行只有同一列有内容，则是同一个单元格的换行文本
        rows = mergeSingleColumnRows(rows);

        return new TableResult(rows, headers);
    }

    /** 转置表格：列头在左侧，第一列是标签，后续列是值。 */
    private static TableResult recognizeTransposed(
            List<List<OcrRow>> rowGroups, String[] columnHeaders) {

        if (rowGroups.isEmpty()) {
            return new TableResult(Collections.emptyList(),
                    columnHeaders != null ? columnHeaders : new String[0]);
        }

        // 每行：第一个 OCR 是标签，其余是值
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (List<OcrRow> group : rowGroups) {
            if (group.isEmpty()) continue;
            String label = group.get(0).getFeature().trim();
            StringBuilder val = new StringBuilder();
            for (int i = 1; i < group.size(); i++) {
                String f = group.get(i).getFeature().trim();
                if (!f.isEmpty()) {
                    if (val.length() > 0) val.append(" ");
                    val.append(f);
                }
            }
            labels.add(label);
            values.add(val.toString());
        }

        // 构建结果：列头 = [属性名, 值]
        String[] headers = columnHeaders != null && columnHeaders.length >= 2
                ? columnHeaders
                : new String[]{"属性", "值"};
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            rows.add(new String[]{labels.get(i), values.get(i)});
        }

        return new TableResult(rows, headers);
    }

    /** 按 Y 坐标分组 OCR 行为行（Y 相近的归一组）。 */
    private static List<List<OcrRow>> groupByY(List<OcrRow> ocrRows) {
        List<OcrRow> sorted = new ArrayList<>(ocrRows);
        sorted.sort(Comparator.comparingDouble(OcrRow::getStartY)
                .thenComparingDouble(OcrRow::getStartX));

        List<List<OcrRow>> groups = new ArrayList<>();
        for (OcrRow row : sorted) {
            if (!row.hasBbox()) continue;
            boolean added = false;
            for (List<OcrRow> group : groups) {
                OcrRow first = group.get(0);
                if (Math.abs(row.centerY() - first.centerY()) < 12) {
                    group.add(row);
                    group.sort(Comparator.comparingDouble(OcrRow::getStartX));
                    added = true;
                    break;
                }
            }
            if (!added) {
                List<OcrRow> newGroup = new ArrayList<>();
                newGroup.add(row);
                groups.add(newGroup);
            }
        }
        return groups;
    }

    /**
     * 合并连续的单列多行：如果连续 N≥2 行只有同一列有内容，
     * 则是同一单元格的换行文本（如材料规格），合并为一行，文本用 \n 连接。
     */
    static List<String[]> mergeSingleColumnRows(List<String[]> rows) {
        if (rows == null || rows.size() <= 1) {
            return rows;
        }
        List<String[]> merged = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            String[] current = rows.get(i);
            int onlyCol = onlyNonEmptyColumn(current);
            if (onlyCol < 0) {
                merged.add(current);
                i++;
                continue;
            }
            // 向后找连续只有同一列有内容的行
            int j = i + 1;
            while (j < rows.size()) {
                String[] next = rows.get(j);
                int nextCol = onlyNonEmptyColumn(next);
                if (nextCol != onlyCol) break;
                j++;
            }
            if (j - i >= 2) {
                // 合并这些行：文本用 \n 连接
                StringBuilder sb = new StringBuilder(current[onlyCol]);
                for (int k = i + 1; k < j; k++) {
                    String v = rows.get(k)[onlyCol];
                    if (v != null && !v.isEmpty()) {
                        sb.append('\n').append(v);
                    }
                }
                String[] mergedRow = current.clone();
                mergedRow[onlyCol] = sb.toString();
                merged.add(mergedRow);
            } else {
                merged.add(current);
            }
            i = j;
        }
        return merged;
    }

    /** 返回该行唯一有内容的列索引，不唯一返回 -1。 */
    private static int onlyNonEmptyColumn(String[] row) {
        int found = -1;
        for (int c = 0; c < row.length; c++) {
            if (row[c] != null && !row[c].trim().isEmpty()) {
                if (found >= 0) return -1; // 多列有内容
                found = c;
            }
        }
        return found;
    }

    /** 根据 X 坐标找到最近的列（基于列头行的 X 分布）。 */
    private static int findNearestColumn(double x, List<OcrRow> headerRow) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < headerRow.size(); i++) {
            double dist = Math.abs(headerRow.get(i).centerX() - x);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
