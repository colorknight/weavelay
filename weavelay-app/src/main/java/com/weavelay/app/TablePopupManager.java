package com.weavelay.app;

import com.weavelay.core.extract.TableRecognizer;
import com.weavelay.core.layout.CellRect;
import com.weavelay.core.model.OcrRow;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TablePosition;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 表格校验结果弹窗管理。
 * 从 WeaveLayApp 迁出以减少主文件大小。
 */
public final class TablePopupManager {

    private TablePopupManager() {}

    /** 弹窗内点选某一数据格：行/列均为数据区下标（不含表头行）。 */
    @FunctionalInterface
    public interface TableCellPickHandler {
        void onPick(int dataRowIndex, int dataColIndex, String cellText);
    }

    /** 原稿裁图上的框：OCR 文字或物理空格；dataRow/dataCol 与弹窗表格一致。 */
    public record CropCell(int dataRow, int dataCol, double x, double y, double w, double h, boolean physical) {
        public CropCell(int dataRow, int dataCol, double x, double y, double w, double h) {
            this(dataRow, dataCol, x, y, w, h, false);
        }

        CropCell at(int row, int col) {
            return new CropCell(row, col, x, y, w, h, physical);
        }
    }

    public static List<CropCell> collectOcrHits(
            String slotDefLabel, List<CellRect> cells, List<OcrRow> ocrRows) {
        List<CropCell> hits = new ArrayList<>();
        buildTableMarkdown(slotDefLabel, cells, ocrRows, hits);
        return hits;
    }

    /**
     * 纯后端建表：根据 label、CellRect 和 OCR 行，生成 Markdown 表格字符串。
     * 与 {@link #show} 共用同一套建网格逻辑，不弹窗。
     */
    public static String buildTableMarkdown(String slotDefLabel,
                                            List<CellRect> cells,
                                            List<OcrRow> ocrRows) {
        return buildTableMarkdown(slotDefLabel, cells, ocrRows, null);
    }

    private static String buildTableMarkdown(String slotDefLabel,
                                             List<CellRect> cells,
                                             List<OcrRow> ocrRows,
                                             List<CropCell> hitsOut) {
        String[] cols = new String[0];
        String tableName = "表格";
        if (slotDefLabel != null) {
            if (slotDefLabel.startsWith("*")) {
                int colon = slotDefLabel.indexOf(':');
                if (colon > 1) {
                    tableName = slotDefLabel.substring(1, colon).trim();
                    String rest = slotDefLabel.substring(colon + 1).trim();
                    int eq = rest.indexOf('=');
                    String colsStr;
                    if (eq >= 0) {
                        colsStr = rest.substring(0, eq).trim();
                    } else {
                        colsStr = rest;
                    }
                    String[] rawCols = colsStr.isEmpty() ? new String[0] : colsStr.split(",");
                    List<String> finalCols = new ArrayList<>();
                    for (String raw : rawCols) {
                        String trimmed = raw.trim();
                        if (!trimmed.isEmpty()) finalCols.add(trimmed);
                    }
                    cols = finalCols.toArray(new String[0]);
                } else {
                    tableName = slotDefLabel.substring(1).trim();
                }
            } else {
                tableName = slotDefLabel;
            }
        }
        final String[] headerCols = cols;
        final String popupTitle = tableName;

        int maxRow;
        final int maxCol;
        String[][] grid;

        if (headerCols.length > 0) {
            boolean useCells = cells != null && !cells.isEmpty()
                    && cells.stream().anyMatch(c -> c.getRow() >= 0 && c.getCol() >= 0);
            if (useCells) {
                maxCol = headerCols.length;
                int maxCellRow = 0;
                for (CellRect cell : cells) {
                    if (cell.getRow() > maxCellRow) maxCellRow = cell.getRow();
                }
                maxRow = maxCellRow + 1;
                if (maxRow < 2) maxRow = 2;
                grid = new String[maxRow][maxCol];
                for (int r = 0; r < maxRow; r++) {
                    for (int c = 0; c < maxCol; c++) {
                        grid[r][c] = "";
                    }
                }
                for (int c = 0; c < headerCols.length; c++) {
                    grid[0][c] = headerCols[c].trim();
                }
                Set<String> headerSet = new HashSet<>();
                for (String h : headerCols) { String n = h.trim(); if (!n.isEmpty()) headerSet.add(n); }
                List<CellRect> dataCells = new ArrayList<>();
                for (CellRect cell : cells) {
                    String t = cell.getText() != null ? cell.getText().trim() : "";
                    if (cell.getRowspan() == 1 && !t.isEmpty() && headerSet.contains(t)) continue;
                    dataCells.add(cell);
                }
                int cellRows = dataCells.stream().mapToInt(CellRect::getRow).max().orElse(-1) + 1;
                boolean isGrid = cellRows >= 2;
                int cellRowMin = dataCells.stream().mapToInt(CellRect::getRow).min().orElse(0);
                int cellRowMax = dataCells.stream().mapToInt(CellRect::getRow).max().orElse(0);
                int cellColMin = dataCells.stream().mapToInt(CellRect::getCol).min().orElse(0);
                int cellColMax = dataCells.stream().mapToInt(CellRect::getCol).max().orElse(0);

                if (isGrid) {
                    maxRow = cellRows + 1;
                    grid = new String[maxRow][maxCol];
                    for (int r = 0; r < maxRow; r++) {
                        for (int c = 0; c < maxCol; c++) {
                            grid[r][c] = "";
                        }
                    }
                    for (int c = 0; c < headerCols.length; c++) {
                        grid[0][c] = headerCols[c].trim();
                    }
                    List<CellRect> coreCells = filterCoreCells(
                            dataCells, cellRowMin, cellRowMax, cellColMin, cellColMax);
                    int[] margin = refineMarginOffsets(
                            dataCells, resolveMarginOffsets(dataCells, coreCells), maxCol);
                    boolean hasTopMargin = margin[0] == 1;
                    boolean hasLeftMargin = margin[1] == 1;
                    fillGridByOcrPairing(
                            popupTitle, grid, maxRow, maxCol, headerCols,
                            ocrRows, cells, dataCells, coreCells,
                            hasTopMargin, hasLeftMargin, hitsOut);
                } else {
                    List<CellRect> coreCells = filterCoreCells(
                            dataCells, cellRowMin, cellRowMax, cellColMin, cellColMax);
                    int[] margin = refineMarginOffsets(
                            dataCells, resolveMarginOffsets(dataCells, coreCells), maxCol);
                    boolean hasLeftMargin = margin[1] == 1;
                    Map<Integer, List<String>> colLabelsMap = new LinkedHashMap<>();
                    Map<Integer, List<String>> colValuesMap = new LinkedHashMap<>();
                    for (OcrRow ocr : ocrRows) {
                        String text = ocr.getFeature() != null ? ocr.getFeature().trim() : "";
                        if (text.isEmpty()) continue;
                        CellRect bestCell = null;
                        double bestOverlap = 0;
                        for (CellRect cell : coreCells) {
                            double ox = Math.max(ocr.getStartX(), cell.getX());
                            double oy = Math.max(ocr.getStartY(), cell.getY());
                            double ex = Math.min(ocr.getEndX(), cell.getEndX());
                            double ey = Math.min(ocr.getEndY(), cell.getEndY());
                            if (ex > ox && ey > oy) {
                                double overlap = (ex - ox) * (ey - oy);
                                if (overlap > bestOverlap) {
                                    bestOverlap = overlap;
                                    bestCell = cell;
                                }
                            }
                        }
                        if (bestCell != null) {
                            int col = bestCell.getCol();
                            if (hasLeftMargin) col--;
                            if (col < 0 || col >= maxCol) continue;
                            if (bestCell.getRowspan() >= 2) {
                                colLabelsMap.computeIfAbsent(col, k -> new ArrayList<>()).add(text);
                            } else {
                                colValuesMap.computeIfAbsent(col, k -> new ArrayList<>()).add(text);
                            }
                        }
                    }
                    String[] colLabels = new String[maxCol];
                    String[] colValues = new String[maxCol];
                    for (int c = 0; c < maxCol; c++) { colLabels[c] = ""; colValues[c] = ""; }
                    for (Map.Entry<Integer, List<String>> e : colLabelsMap.entrySet()) {
                        int col = e.getKey();
                        if (col >= 0 && col < maxCol) {
                            colLabels[col] = String.join(" ", e.getValue());
                        }
                    }
                    for (Map.Entry<Integer, List<String>> e : colValuesMap.entrySet()) {
                        int col = e.getKey();
                        if (col >= 0 && col < maxCol) {
                            colValues[col] = String.join("\n", e.getValue());
                        }
                    }
                    int dr = 1;
                    boolean hasLabels = false, hasValues = false;
                    for (int c = 0; c < maxCol; c++) {
                        if (!colLabels[c].isEmpty()) { grid[dr][c] = colLabels[c]; hasLabels = true; }
                    }
                    if (hasLabels) dr++;
                    for (int c = 0; c < maxCol; c++) {
                        if (!colValues[c].isEmpty()) { grid[dr][c] = colValues[c]; hasValues = true; }
                    }
                    maxRow = 1 + (hasLabels ? 1 : 0) + (hasValues ? 1 : 0);
                }
                // 保留全空行，保证弹窗行序与物理格一致（点行高亮才对得上）
            } else {
                List<OcrRow> cleanedRows = OcrUtils.removeVerticalLabelFragments(ocrRows, headerCols);
                var result = TableRecognizer.recognize(cleanedRows, headerCols, false);
                int dataStart = 0;
                if (!result.rows.isEmpty()) {
                    String[] first = result.rows.get(0);
                    int match = 0;
                    for (int c = 0; c < first.length; c++) {
                        if (first[c] == null || first[c].isEmpty()) continue;
                        for (int hc = 0; hc < headerCols.length; hc++) {
                            String h = headerCols[hc].trim();
                            if (first[c].equals(h) || h.contains(first[c]) || first[c].contains(h)
                                    || OcrUtils.levenshtein(first[c], h) <= 2) {
                                match++;
                                break;
                            }
                        }
                    }
                    if (match >= 2 && match >= headerCols.length / 2) {
                        dataStart = 1;
                    }
                }
                int dataRows = result.rows.size() - dataStart;
                maxRow = dataRows + 1;
                maxCol = Math.max(headerCols.length, result.colCount);
                grid = new String[maxRow][maxCol];
                for (int c = 0; c < maxCol; c++) {
                    grid[0][c] = c < headerCols.length ? headerCols[c].trim() : "Col" + (c + 1);
                }
                for (int r = dataStart; r < result.rows.size(); r++) {
                    String[] row = result.rows.get(r);
                    int dr = r - dataStart + 1;
                    for (int c = 0; c < maxCol; c++) {
                        grid[dr][c] = (c < row.length && row[c] != null) ? row[c] : "";
                    }
                }
            }
        } else {
            var result = TableRecognizer.recognize(ocrRows, null, false);
            maxRow = result.rows.size() + 1;
            maxCol = result.colCount;
            grid = new String[maxRow][maxCol];
            for (int c = 0; c < maxCol; c++) {
                grid[0][c] = result.columnHeaders.length > c ? result.columnHeaders[c] : "Col" + (c + 1);
            }
            for (int r = 0; r < result.rows.size(); r++) {
                String[] row = result.rows.get(r);
                for (int c = 0; c < maxCol; c++) {
                    grid[r + 1][c] = (c < row.length && row[c] != null) ? row[c] : "";
                }
            }
        }

        String markdown = MarkdownUtils.gridToMarkdown(grid, maxRow, maxCol, popupTitle);
        return markdown;
    }

    /**
     * 应用模式：可编辑表格弹窗（所见即所得：普通文字；确定后写回缓存 / Excel）。
     */
    public static void showEditor(String title,
                                  String[] headers,
                                  String existingMarkdown,
                                  java.util.function.Consumer<String> onSaved) {
        showEditor(title, headers, existingMarkdown, null, onSaved);
    }

    public static void showEditor(String title,
                                  String[] headers,
                                  String existingMarkdown,
                                  javafx.scene.image.Image regionPreview,
                                  java.util.function.Consumer<String> onSaved) {
        showEditor(title, headers, existingMarkdown, regionPreview, null, onSaved, null, null);
    }

    /**
     * 以物理格结构为准合并用户改过的表：保留结构行数；
     * 编辑稿里已有的格子（含刻意清空）一律以编辑稿为准，只有编辑稿缺行/缺列时才回退 OCR 结构。
     */
    public static String mergeMarkdownPreserveStructure(String structureMd, String editedMd) {
        if (structureMd == null || structureMd.isBlank()) {
            return editedMd != null ? editedMd : "";
        }
        if (editedMd == null || editedMd.isBlank()) {
            return structureMd;
        }
        List<List<String>> sRows = parseMarkdownRows(structureMd);
        List<List<String>> eRows = parseMarkdownRows(editedMd);
        if (sRows.isEmpty()) {
            return editedMd;
        }
        String title = "表格";
        for (String line : structureMd.split("\n")) {
            String t = line.trim();
            if (t.startsWith("### ")) {
                title = t.substring(4).trim();
                break;
            }
        }
        List<String> header = new ArrayList<>(sRows.get(0));
        int cols = header.size();
        String[] hdrArr = header.toArray(new String[0]);
        List<List<String>> sData = sRows.size() > 1 ? sRows.subList(1, sRows.size()) : List.of();
        List<List<String>> eData;
        if (!eRows.isEmpty() && looksLikeHeaderRow(eRows.get(0), hdrArr)) {
            eData = eRows.size() > 1 ? eRows.subList(1, eRows.size()) : List.of();
        } else {
            eData = eRows;
        }
        // 物理格常含空行占位；槽位 markdown 常被压成紧凑行。按「非空数据行」对齐再写回，
        // 避免空行错位把「转热处理」等拼到错误工序号上。
        List<Integer> sNonEmptyIdx = nonEmptyDataRowIndexes(sData);
        List<List<String>> eCompact = compactDataRows(eData);
        if (sNonEmptyIdx.isEmpty() && eCompact.isEmpty()) {
            return structureMd;
        }
        // 物理格全空时无可对齐内容，沿用已编辑 Markdown
        if (sNonEmptyIdx.isEmpty()) {
            return editedMd;
        }
        // 非空行数对不上：以物理格结构为准，避免错位覆盖
        if (sNonEmptyIdx.size() != eCompact.size()) {
            if (sData.size() != eData.size()) {
                return structureMd;
            }
            // 行数相同但仍含空行差：退回按下标合并（旧行为）
            return mergeByRowIndex(title, header, cols, sData, eData);
        }
        String[][] grid = new String[sData.size() + 1][cols];
        for (int c = 0; c < cols; c++) {
            grid[0][c] = header.get(c) == null ? "" : header.get(c);
        }
        for (int r = 0; r < sData.size(); r++) {
            for (int c = 0; c < cols; c++) {
                String sv = (c < sData.get(r).size()) ? sData.get(r).get(c) : "";
                grid[r + 1][c] = sv != null ? sv : "";
            }
        }
        for (int i = 0; i < sNonEmptyIdx.size(); i++) {
            int r = sNonEmptyIdx.get(i);
            List<String> evRow = eCompact.get(i);
            for (int c = 0; c < cols; c++) {
                if (c < evRow.size() && evRow.get(c) != null && !evRow.get(c).isBlank()) {
                    grid[r + 1][c] = evRow.get(c);
                }
            }
        }
        return MarkdownUtils.gridToMarkdown(grid, sData.size() + 1, cols, title);
    }

    /**
     * 槽位 Markdown 相对物理格结构是否整列右偏（如工序名称进了设备.名称）。
     * 用于弹窗避免错列表盖住本地 OCR 配对结果。
     */
    public static boolean seemsColumnShifted(String structureMd, String editedMd) {
        if (structureMd == null || structureMd.isBlank()
                || editedMd == null || editedMd.isBlank()) {
            return false;
        }
        List<List<String>> sRows = parseMarkdownRows(structureMd);
        List<List<String>> eRows = parseMarkdownRows(editedMd);
        if (sRows.size() < 2 || eRows.size() < 2) {
            return false;
        }
        List<String> header = sRows.get(0);
        List<List<String>> sData = sRows.subList(1, sRows.size());
        List<List<String>> eData;
        if (looksLikeHeaderRow(eRows.get(0), header.toArray(new String[0]))) {
            eData = eRows.size() > 1 ? eRows.subList(1, eRows.size()) : List.of();
        } else {
            eData = eRows;
        }
        List<List<String>> sCompact = compactDataRows(sData);
        List<List<String>> eCompact = compactDataRows(eData);
        int n = Math.min(sCompact.size(), eCompact.size());
        if (n <= 0) {
            return false;
        }
        int shifted = 0;
        int compared = 0;
        for (int i = 0; i < n && i < 8; i++) {
            int sCol = firstNonBlankCol(sCompact.get(i), 1); // 跳过工序号列
            int eCol = firstNonBlankCol(eCompact.get(i), 1);
            if (sCol < 0 || eCol < 0) {
                continue;
            }
            compared++;
            if (eCol != sCol) {
                shifted++;
            }
        }
        return compared >= 2 && shifted * 2 >= compared;
    }

    private static int firstNonBlankCol(List<String> row, int fromCol) {
        if (row == null) {
            return -1;
        }
        for (int c = Math.max(0, fromCol); c < row.size(); c++) {
            if (row.get(c) != null && !row.get(c).isBlank()) {
                return c;
            }
        }
        return -1;
    }

    private static String mergeByRowIndex(
            String title,
            List<String> header,
            int cols,
            List<List<String>> sData,
            List<List<String>> eData) {
        int outRows = Math.max(sData.size(), eData.size());
        String[][] grid = new String[outRows + 1][cols];
        for (int c = 0; c < cols; c++) {
            grid[0][c] = header.get(c) == null ? "" : header.get(c);
        }
        for (int r = 0; r < outRows; r++) {
            for (int c = 0; c < cols; c++) {
                String sv = (r < sData.size() && c < sData.get(r).size()) ? sData.get(r).get(c) : "";
                boolean hasEdit = r < eData.size() && c < eData.get(r).size();
                if (hasEdit) {
                    String ev = eData.get(r).get(c);
                    grid[r + 1][c] = ev != null ? ev : "";
                } else {
                    grid[r + 1][c] = sv != null ? sv : "";
                }
            }
        }
        return MarkdownUtils.gridToMarkdown(grid, outRows + 1, cols, title);
    }

    private static boolean isBlankDataRow(List<String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String c : row) {
            if (c != null && !c.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> nonEmptyDataRowIndexes(List<List<String>> data) {
        List<Integer> idx = new ArrayList<>();
        if (data == null) {
            return idx;
        }
        for (int i = 0; i < data.size(); i++) {
            if (!isBlankDataRow(data.get(i))) {
                idx.add(i);
            }
        }
        return idx;
    }

    private static List<List<String>> compactDataRows(List<List<String>> data) {
        List<List<String>> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (List<String> row : data) {
            if (!isBlankDataRow(row)) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * 表格编辑：非模态。有原稿裁图时上下对照：上为 PDF 裁图，下为识别表；点一边另一边跟上。
     */
    public static void showEditor(String title,
                                  String[] headers,
                                  String existingMarkdown,
                                  javafx.scene.image.Image regionPreview,
                                  java.util.function.Consumer<String> onSaved,
                                  TableCellPickHandler onCellPicked) {
        showEditor(title, headers, existingMarkdown, regionPreview, null, onSaved, onCellPicked, null);
    }

    public static void showEditor(String title,
                                  String[] headers,
                                  String existingMarkdown,
                                  javafx.scene.image.Image regionPreview,
                                  List<CropCell> cropCells,
                                  java.util.function.Consumer<String> onSaved,
                                  TableCellPickHandler onCellPicked) {
        showEditor(title, headers, existingMarkdown, regionPreview, cropCells, onSaved, onCellPicked, null);
    }

    public static void showEditor(String title,
                                  String[] headers,
                                  String existingMarkdown,
                                  javafx.scene.image.Image regionPreview,
                                  List<CropCell> cropCells,
                                  java.util.function.Consumer<String> onSaved,
                                  TableCellPickHandler onCellPicked,
                                  javafx.stage.Window owner) {
        String tableTitle = (title == null || title.isBlank()) ? "表格" : title.trim();
        String[] cols = headers == null ? new String[0] : headers;
        List<List<String>> parsed = parseMarkdownRows(existingMarkdown);

        if (!parsed.isEmpty()) {
            List<String> first = parsed.get(0);
            if (cols.length == 0) {
                cols = first.toArray(new String[0]);
            }
            if (looksLikeHeaderRow(first, cols)) {
                parsed = parsed.subList(1, parsed.size());
            }
        }
        if (cols.length == 0) {
            cols = new String[]{"列1", "列2", "列3"};
        }
        final String[] headerCols = cols;
        final int colCount = headerCols.length;

        javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data =
                javafx.collections.FXCollections.observableArrayList();
        for (List<String> row : parsed) {
            javafx.collections.ObservableList<String> line =
                    javafx.collections.FXCollections.observableArrayList();
            for (int c = 0; c < colCount; c++) {
                line.add(c < row.size() && row.get(c) != null ? row.get(c) : "");
            }
            data.add(line);
        }
        if (data.isEmpty()) {
            javafx.collections.ObservableList<String> empty =
                    javafx.collections.FXCollections.observableArrayList();
            for (int c = 0; c < colCount; c++) empty.add("");
            data.add(empty);
        }
        final List<CropCell> hits = new ArrayList<>();
        if (cropCells != null) {
            hits.addAll(cropCells);
        }
        final java.util.function.BiConsumer<Integer, Integer>[] highlightCrop =
                new java.util.function.BiConsumer[1];
        final Runnable[] pickNow = new Runnable[1];
        final Runnable[] refreshMove = new Runnable[1];
        final int[] moveRow = {-1};
        final int[] moveCol = {-1};

        javafx.scene.control.TableView<javafx.collections.ObservableList<String>> table =
                new javafx.scene.control.TableView<>(data);
        table.setEditable(true);
        table.setFixedCellSize(32);
        table.setStyle("-fx-font-size: 14px; -fx-font-family: 'Microsoft YaHei';");
        table.setColumnResizePolicy(javafx.scene.control.TableView.UNCONSTRAINED_RESIZE_POLICY);
        // 点格对照 PDF；增删行用左侧勾选，不靠整行染色
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        table.setSortPolicy(null);
        table.getStyleClass().add("app-table-popup");
        table.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(javafx.collections.ObservableList<String> item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("move-target-row");
                if (!empty && getIndex() >= 0 && getIndex() == moveRow[0]) {
                    getStyleClass().add("move-target-row");
                }
            }
        });

        javafx.collections.ObservableList<Boolean> rowChecked =
                javafx.collections.FXCollections.observableArrayList();
        for (int i = 0; i < data.size(); i++) {
            rowChecked.add(false);
        }

        javafx.scene.control.TableColumn<javafx.collections.ObservableList<String>, Void> checkCol =
                new javafx.scene.control.TableColumn<>("行");
        checkCol.setPrefWidth(72);
        checkCol.setMinWidth(64);
        checkCol.setMaxWidth(84);
        checkCol.setEditable(false);
        checkCol.setSortable(false);
        checkCol.setResizable(false);
        checkCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox();
            private final Label num = new Label();
            private final HBox boxRow = new HBox(6, num, box);
            private boolean syncing;

            {
                box.setFocusTraversable(false);
                num.setMinWidth(18);
                num.setCursor(javafx.scene.Cursor.HAND);
                num.setStyle("-fx-font-size:12px; -fx-text-fill:#3a5555; -fx-underline:true;");
                boxRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                boxRow.setPadding(new javafx.geometry.Insets(0, 4, 0, 6));
                box.setOnAction(ev -> {
                    int i = getIndex();
                    if (i < 0 || i >= rowChecked.size() || syncing) {
                        return;
                    }
                    rowChecked.set(i, box.isSelected());
                });
                num.setOnMouseClicked(ev -> {
                    int i = getIndex();
                    if (i < 0 || i >= data.size()) {
                        return;
                    }
                    if (moveRow[0] == i) {
                        moveRow[0] = -1;
                    } else {
                        moveRow[0] = i;
                    }
                    moveCol[0] = -1;
                    if (refreshMove[0] != null) {
                        refreshMove[0].run();
                    }
                    ev.consume();
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= data.size()) {
                    setGraphic(null);
                    return;
                }
                while (rowChecked.size() < data.size()) {
                    rowChecked.add(false);
                }
                while (rowChecked.size() > data.size()) {
                    rowChecked.remove(rowChecked.size() - 1);
                }
                syncing = true;
                box.setSelected(Boolean.TRUE.equals(rowChecked.get(getIndex())));
                syncing = false;
                num.setText(String.valueOf(getIndex() + 1));
                setGraphic(boxRow);
            }
        });
        table.getColumns().add(checkCol);

        for (int c = 0; c < colCount; c++) {
            final int colIdx = c;
            javafx.scene.control.TableColumn<javafx.collections.ObservableList<String>, String> col =
                    new javafx.scene.control.TableColumn<>(headerCols[c]);
            col.setPrefWidth(Math.max(100, Math.min(220, 900 / Math.max(1, colCount))));
            col.setSortable(false);
            col.setCellValueFactory(cd -> {
                javafx.collections.ObservableList<String> row = cd.getValue();
                String v = colIdx < row.size() ? row.get(colIdx) : "";
                return new javafx.beans.property.SimpleStringProperty(v == null ? "" : v);
            });
            col.setEditable(true);
            col.setCellFactory(c2 -> new DoubleClickEditCell(colIdx, pickNow, moveCol));
            col.setOnEditCommit(ev -> {
                javafx.collections.ObservableList<String> row = ev.getRowValue();
                while (row.size() <= colIdx) row.add("");
                row.set(colIdx, ev.getNewValue() == null ? "" : ev.getNewValue());
            });
            table.getColumns().add(col);
        }

        Stage popup = new Stage();
        popup.initModality(javafx.stage.Modality.NONE);
        if (owner != null) {
            popup.initOwner(owner);
        }
        popup.setTitle(tableTitle + (regionPreview != null ? " — 左原稿 / 右识别" : " — 核对 OCR"));
        Label rowHint = new Label("勾选后删行。点行号选行、点列名选列，再按箭头移动。单击对照，双击改字");
        rowHint.setStyle("-fx-font-size:11px; -fx-text-fill:#7a9494;");
        Button addRow = new Button("添加行");
        addRow.setOnAction(e -> {
            javafx.collections.ObservableList<String> empty =
                    javafx.collections.FXCollections.observableArrayList();
            for (int i = 0; i < colCount; i++) empty.add("");
            int insertAt = data.size();
            for (int i = rowChecked.size() - 1; i >= 0; i--) {
                if (Boolean.TRUE.equals(rowChecked.get(i))) {
                    insertAt = i + 1;
                    break;
                }
            }
            data.add(insertAt, empty);
            rowChecked.add(insertAt, false);
            table.scrollTo(insertAt);
            table.refresh();
        });
        Button delRow = new Button("删除行");
        delRow.setOnAction(e -> {
            java.util.List<Integer> toRemove = new java.util.ArrayList<>();
            for (int i = 0; i < rowChecked.size() && i < data.size(); i++) {
                if (Boolean.TRUE.equals(rowChecked.get(i))) {
                    toRemove.add(i);
                }
            }
            if (toRemove.isEmpty()) {
                rowHint.setText("请先勾选要删的行");
                return;
            }
            if (toRemove.size() >= data.size()) {
                rowHint.setText("至少保留一行");
                return;
            }
            for (int i = toRemove.size() - 1; i >= 0; i--) {
                int idx = toRemove.get(i);
                data.remove(idx);
                if (idx < rowChecked.size()) {
                    rowChecked.remove(idx);
                }
            }
            rowHint.setText("勾选后删行。点行号选行、点列名选列，再按箭头移动");
            table.refresh();
        });
        Button scopeWhole = new Button("整表");
        scopeWhole.setFocusTraversable(false);
        Label scopeTarget = new Label("");
        scopeTarget.setStyle("-fx-font-size:12px; -fx-text-fill:#E65100; -fx-font-weight:bold;");
        Label moveLbl = new Label("移动");
        moveLbl.setStyle("-fx-font-size:11px; -fx-text-fill:#5a7373;");
        refreshMove[0] = () -> {
            boolean whole = moveRow[0] < 0 && moveCol[0] < 0;
            scopeWhole.setStyle(whole
                    ? "-fx-background-color:#FFCC80; -fx-font-weight:bold;"
                    : "");
            if (moveRow[0] >= 0 && moveRow[0] < data.size()) {
                scopeTarget.setText("第" + (moveRow[0] + 1) + "行");
            } else if (moveCol[0] >= 0 && moveCol[0] < colCount) {
                scopeTarget.setText(headerCols[moveCol[0]] + "列");
            } else {
                moveRow[0] = -1;
                moveCol[0] = -1;
                scopeTarget.setText("");
            }
            for (int i = 0; i < colCount && i + 1 < table.getColumns().size(); i++) {
                var tc = table.getColumns().get(i + 1);
                tc.getStyleClass().remove("move-target-col");
                if (moveCol[0] == i) {
                    tc.getStyleClass().add("move-target-col");
                }
            }
            table.refresh();
        };
        scopeWhole.setOnAction(e -> {
            moveRow[0] = -1;
            moveCol[0] = -1;
            refreshMove[0].run();
        });
        java.util.function.BiConsumer<Integer, Integer> doMove = (dx, dy) -> {
            commitInProgressTableEdit(table, data, colCount);
            int fr = moveRow[0];
            int fc = moveCol[0];
            if (fr >= 0) {
                if (dx != 0) {
                    shiftRowHorizontal(data, colCount, fr, dx);
                    shiftHits(hits, dx, 0, data.size(), colCount, fr, null);
                }
                if (dy != 0 && swapRows(data, rowChecked, fr, fr + dy)) {
                    swapHitRows(hits, fr, fr + dy);
                    moveRow[0] = fr + dy;
                }
            } else if (fc >= 0) {
                if (dx != 0) {
                    int dest = fc + dx;
                    if (swapCols(data, colCount, fc, dest)) {
                        swapHitCols(hits, fc, dest);
                        moveCol[0] = dest;
                    }
                }
                if (dy != 0) {
                    shiftColVertical(data, colCount, fc, dy);
                    shiftHits(hits, 0, dy, data.size(), colCount, null, fc);
                }
            } else {
                shiftAll(data, colCount, dx, dy);
                shiftHits(hits, dx, dy, data.size(), colCount, null, null);
            }
            refreshMove[0].run();
            TablePosition<?, ?> pos = table.getFocusModel().getFocusedCell();
            int hr = pos == null ? 0 : pos.getRow();
            int hc = pos == null ? 0 : pos.getColumn() - 1;
            if (highlightCrop[0] != null && hr >= 0 && hc >= 0) {
                highlightCrop[0].accept(Math.max(0, Math.min(hr, data.size() - 1)),
                        Math.max(0, Math.min(hc, colCount - 1)));
            }
        };
        Button mvLeft = moveArrowBtn("←", e -> doMove.accept(-1, 0));
        Button mvRight = moveArrowBtn("→", e -> doMove.accept(1, 0));
        Button mvUp = moveArrowBtn("↑", e -> doMove.accept(0, -1));
        Button mvDown = moveArrowBtn("↓", e -> doMove.accept(0, 1));
        Button ok = new Button("确定");
        Button cancel = new Button("取消");
        ok.setDefaultButton(true);
        cancel.setCancelButton(true);
        // 在失焦取消编辑之前先提交：点确定时 TextField 焦点会先丢掉，仅靠 onAction 容易丢刚改的字
        ok.addEventFilter(MouseEvent.MOUSE_PRESSED, e ->
                commitInProgressTableEdit(table, data, colCount));
        ok.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                commitInProgressTableEdit(table, data, colCount);
            }
        });
        ok.setOnAction(e -> {
            commitInProgressTableEdit(table, data, colCount);
            ok.requestFocus();
            String[][] grid = new String[data.size() + 1][colCount];
            for (int c = 0; c < colCount; c++) {
                grid[0][c] = headerCols[c];
            }
            for (int r = 0; r < data.size(); r++) {
                javafx.collections.ObservableList<String> row = data.get(r);
                for (int c = 0; c < colCount; c++) {
                    grid[r + 1][c] = c < row.size() && row.get(c) != null ? row.get(c) : "";
                }
            }
            String md = MarkdownUtils.gridToMarkdown(grid, data.size() + 1, colCount, tableTitle);
            if (onSaved != null) {
                onSaved.accept(md);
            }
            popup.close();
        });
        cancel.setOnAction(e -> popup.close());

        HBox bar = new HBox(8, addRow, delRow, moveLbl, scopeWhole, scopeTarget, mvLeft, mvRight, mvUp, mvDown,
                ok, cancel, rowHint);
        bar.setPadding(new javafx.geometry.Insets(8));
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox tableBox = new VBox(table, bar);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        tableBox.setPadding(new javafx.geometry.Insets(8));
        tableBox.setStyle("-fx-background-color:#f3f8f8;");

        javafx.scene.Parent root;
        if (regionPreview != null && regionPreview.getWidth() > 1) {
            OriginalPreview preview = attachOriginalPreview(
                    regionPreview, hits, table, data, onCellPicked);
            highlightCrop[0] = preview.highlight();
            javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane();
            split.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
            split.getItems().addAll(preview.node(), tableBox);
            split.setDividerPositions(0.46);
            root = split;
        } else {
            root = tableBox;
        }

        Runnable notifyPick = () -> {
            TablePosition<?, ?> pos = table.getFocusModel().getFocusedCell();
            if (pos == null || pos.getRow() < 0 || pos.getRow() >= data.size()) {
                var selected = table.getSelectionModel().getSelectedCells();
                if (selected == null || selected.isEmpty()) {
                    return;
                }
                pos = selected.get(0);
            }
            if (pos == null || pos.getRow() < 0 || pos.getRow() >= data.size()) {
                return;
            }
            // 0 = 勾选列
            javafx.scene.control.TableColumn<?, ?> tc = pos.getTableColumn();
            int colIdx = tc == null ? pos.getColumn() : table.getColumns().indexOf(tc);
            if (colIdx < 0) {
                colIdx = pos.getColumn();
            }
            int dc = colIdx - 1;
            if (dc < 0) {
                return;
            }
            if (dc >= colCount) {
                dc = 0;
            }
            int dr = pos.getRow();
            javafx.collections.ObservableList<String> row = data.get(dr);
            String txt = dc < row.size() && row.get(dc) != null ? row.get(dc) : "";
            if (highlightCrop[0] != null) {
                highlightCrop[0].accept(dr, dc);
            }
            if (onCellPicked != null) {
                onCellPicked.onPick(dr, dc, txt);
            }
        };
        pickNow[0] = notifyPick;
        table.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                return;
            }
            javafx.scene.Node n = e.getPickResult() == null ? null : e.getPickResult().getIntersectedNode();
            while (n != null && n != table) {
                if (n.getStyleClass().contains("column-header")
                        && n instanceof javafx.scene.control.skin.TableColumnHeader hdr) {
                    Object raw = hdr.getTableColumn();
                    int idx = raw == null ? -1 : table.getColumns().indexOf(raw);
                    if (idx == 0) {
                        moveRow[0] = -1;
                        moveCol[0] = -1;
                    } else if (idx > 0) {
                        int col = idx - 1;
                        if (moveCol[0] == col) {
                            moveCol[0] = -1;
                        } else {
                            moveCol[0] = col;
                        }
                        moveRow[0] = -1;
                    }
                    if (refreshMove[0] != null) {
                        refreshMove[0].run();
                    }
                    e.consume();
                    return;
                }
                n = n.getParent();
            }
        });
        table.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                return;
            }
            javafx.application.Platform.runLater(notifyPick);
        });
        table.getFocusModel().focusedCellProperty().addListener((obs, oldCell, pos) ->
                javafx.application.Platform.runLater(notifyPick));
        table.getSelectionModel().getSelectedCells().addListener(
                (javafx.collections.ListChangeListener<TablePosition>) ch -> {
                    while (ch.next()) {
                        if (ch.wasAdded()) {
                            javafx.application.Platform.runLater(notifyPick);
                        }
                    }
                });

        boolean hasPreview = regionPreview != null && regionPreview.getWidth() > 1;
        if (hasPreview) {
            rowHint.setText("左为原稿。单击格子对照（空格圈空格）。点行号选行、点列名选列，橙色即当前移动范围");
        }
        double ownerW = owner != null ? owner.getWidth() : 0;
        double ownerH = owner != null ? owner.getHeight() : 0;
        double w;
        double h;
        if (ownerW >= 400 && ownerH >= 300) {
            w = Math.max(720, ownerW * 0.8);
            h = Math.max(480, ownerH * 0.8);
        } else {
            w = Math.max(hasPreview ? 1100 : 720, Math.min(1480, colCount * 130 + (hasPreview ? 520 : 160)));
            h = hasPreview
                    ? Math.max(560, Math.min(860, data.size() * 34 + 180))
                    : Math.max(420, Math.min(780, data.size() * 34 + 160));
        }
        Scene scene = new Scene(root, w, h);
        var css = TablePopupManager.class.getResource("/com/weavelay/app/ui/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        // 弹窗独立 Scene：主窗 F4 够不到，这里再装一次特殊键盘
        com.weavelay.app.ui.IndustrialSymbolPicker.installSceneHotkey(scene);
        popup.setScene(scene);
        popup.setResizable(true);
        if (owner != null) {
            popup.setX(owner.getX() + Math.max(0, (ownerW - w) / 2));
            popup.setY(owner.getY() + Math.max(0, (ownerH - h) / 2));
        }
        popup.show();
        if (refreshMove[0] != null) {
            refreshMove[0].run();
        }
    }

    /** 单击对照，双击编辑；失焦/点别处把正在输入的内容写回。 */
    private static final class DoubleClickEditCell
            extends javafx.scene.control.cell.TextFieldTableCell<
                javafx.collections.ObservableList<String>, String> {
        private boolean acceptEdit;
        private boolean committing;
        private TextField hookedField;
        private final int dataCol;
        private final int[] moveCol;
        private final Runnable[] pickNow;

        DoubleClickEditCell(int dataCol, Runnable[] pickNow, int[] moveCol) {
            super(new javafx.util.converter.DefaultStringConverter());
            this.dataCol = dataCol;
            this.moveCol = moveCol;
            this.pickNow = pickNow;
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                    return;
                }
                if (isEmpty() || getIndex() < 0) {
                    return;
                }
                var tv = getTableView();
                var col = getTableColumn();
                if (e.getClickCount() >= 2) {
                    // JavaFX 在 clickCount==2 时通常不会调 startEdit，必须自己进编辑
                    acceptEdit = true;
                    if (tv != null && col != null) {
                        tv.getSelectionModel().clearAndSelect(getIndex(), col);
                        tv.getFocusModel().focus(getIndex(), col);
                        tv.edit(getIndex(), col);
                    }
                    e.consume();
                    return;
                }
                if (isEditing()) {
                    return;
                }
                if (tv != null && col != null) {
                    tv.getSelectionModel().clearAndSelect(getIndex(), col);
                    tv.getFocusModel().focus(getIndex(), col);
                }
                if (this.pickNow != null && this.pickNow[0] != null) {
                    javafx.application.Platform.runLater(this.pickNow[0]);
                }
            });
        }

        @Override
        public void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("move-target-cell");
            if (!empty && moveCol != null && moveCol[0] == dataCol) {
                getStyleClass().add("move-target-cell");
            }
        }

        @Override
        public void startEdit() {
            if (!acceptEdit) {
                return;
            }
            super.startEdit();
            acceptEdit = false;
            if (getGraphic() instanceof TextField tf) {
                if (hookedField != tf) {
                    hookedField = tf;
                    tf.setOnAction(ev -> commitFromField(tf));
                    tf.focusedProperty().addListener((obs, was, focused) -> {
                        if (!focused) {
                            commitFromField(tf);
                        }
                    });
                }
                javafx.application.Platform.runLater(() -> {
                    if (isEditing() && getGraphic() == tf) {
                        tf.requestFocus();
                        tf.selectAll();
                    }
                });
            }
        }

        @Override
        public void cancelEdit() {
            if (committing) {
                super.cancelEdit();
                return;
            }
            TextField tf = getGraphic() instanceof TextField t ? t : findTextField(this);
            if (tf != null && isEditing()) {
                commitFromField(tf);
                return;
            }
            super.cancelEdit();
        }

        private void commitFromField(TextField tf) {
            if (committing || tf == null || !isEditing()) {
                return;
            }
            committing = true;
            try {
                String v = tf.getText();
                if (v != null && "（空）".equals(v.trim())) {
                    v = "";
                }
                if (v == null) {
                    v = "";
                }
                writeToRow(v);
                commitEdit(v);
            } finally {
                committing = false;
            }
        }

        private void writeToRow(String v) {
            var tv = getTableView();
            int row = getIndex();
            if (tv == null || row < 0 || row >= tv.getItems().size()) {
                return;
            }
            javafx.collections.ObservableList<String> line = tv.getItems().get(row);
            if (line == null) {
                return;
            }
            while (line.size() <= dataCol) {
                line.add("");
            }
            line.set(dataCol, v);
        }
    }

    private record OriginalPreview(
            javafx.scene.Node node,
            java.util.function.BiConsumer<Integer, Integer> highlight) {}

    private static OriginalPreview attachOriginalPreview(
            javafx.scene.image.Image img,
            List<CropCell> cropCells,
            javafx.scene.control.TableView<javafx.collections.ObservableList<String>> table,
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            TableCellPickHandler onCellPicked) {
        javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        javafx.scene.layout.Pane overlay = new javafx.scene.layout.Pane();
        overlay.setMouseTransparent(true);
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(iv, overlay);
        stack.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(stack);
        scroll.setPannable(true);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color:#d8e0e0;");
        Runnable fit = () -> {
            double vw = Math.max(40, scroll.getViewportBounds().getWidth() - 4);
            double vh = Math.max(40, scroll.getViewportBounds().getHeight() - 4);
            iv.setFitWidth(vw);
            iv.setFitHeight(vh);
            double dw = iv.getLayoutBounds().getWidth();
            double dh = iv.getLayoutBounds().getHeight();
            overlay.setMinSize(dw, dh);
            overlay.setPrefSize(dw, dh);
            overlay.setMaxSize(dw, dh);
        };
        scroll.viewportBoundsProperty().addListener((o, a, b) -> fit.run());
        iv.layoutBoundsProperty().addListener((o, a, b) -> fit.run());

        java.util.function.BiConsumer<Integer, Integer>[] highlightRef =
                new java.util.function.BiConsumer[1];
        java.util.function.BiConsumer<Integer, Integer> highlight = (row, col) -> {
            overlay.getChildren().clear();
            if (img.getWidth() <= 0 || img.getHeight() <= 0 || row == null || col == null) {
                return;
            }
            fit.run();
            double dw = Math.max(iv.getLayoutBounds().getWidth(), iv.getBoundsInLocal().getWidth());
            double dh = Math.max(iv.getLayoutBounds().getHeight(), iv.getBoundsInLocal().getHeight());
            if (dw <= 0 || dh <= 0) {
                dw = iv.getFitWidth();
                dh = iv.getFitHeight();
            }
            if (dw <= 0 || dh <= 0) {
                javafx.application.Platform.runLater(() -> {
                    fit.run();
                    if (highlightRef[0] != null
                            && (iv.getLayoutBounds().getWidth() > 0 || iv.getFitWidth() > 0)) {
                        highlightRef[0].accept(row, col);
                    }
                });
                return;
            }
            double sx = dw / img.getWidth();
            double sy = dh / img.getHeight();
            List<CropCell> ocrHits = cropCellsFor(cropCells, row, col, false);
            List<CropCell> found = ocrHits.isEmpty()
                    ? cropCellsFor(cropCells, row, col, true)
                    : ocrHits;
            overlay.toFront();
            for (CropCell hit : found) {
                javafx.scene.shape.Rectangle mark = new javafx.scene.shape.Rectangle();
                if (hit.physical()) {
                    mark.setFill(javafx.scene.paint.Color.rgb(255, 152, 0, 0.10));
                    mark.setStroke(javafx.scene.paint.Color.web("#E65100"));
                    mark.setStrokeWidth(2);
                    mark.getStrokeDashArray().setAll(7.0, 4.0);
                } else {
                    mark.setFill(javafx.scene.paint.Color.rgb(255, 152, 0, 0.28));
                    mark.setStroke(javafx.scene.paint.Color.web("#E65100"));
                    mark.setStrokeWidth(2);
                }
                mark.setManaged(false);
                mark.setX(hit.x() * sx);
                mark.setY(hit.y() * sy);
                mark.setWidth(Math.max(4, hit.w() * sx));
                mark.setHeight(Math.max(4, hit.h() * sy));
                overlay.getChildren().add(mark);
            }
        };
        highlightRef[0] = highlight;

        iv.setOnMouseClicked(e -> {
            if (cropCells == null || cropCells.isEmpty() || img.getWidth() <= 0) {
                return;
            }
            double dw = iv.getLayoutBounds().getWidth();
            double dh = iv.getLayoutBounds().getHeight();
            if (dw <= 0 || dh <= 0) {
                return;
            }
            double px = e.getX() * (img.getWidth() / dw);
            double py = e.getY() * (img.getHeight() / dh);
            CropCell hit = smallestCropCellAt(cropCells, px, py);
            if (hit == null) {
                return;
            }
            selectEditorCell(table, hit.dataRow(), hit.dataCol());
            highlight.accept(hit.dataRow(), hit.dataCol());
            if (onCellPicked != null && hit.dataRow() >= 0 && hit.dataRow() < data.size()) {
                javafx.collections.ObservableList<String> row = data.get(hit.dataRow());
                int dc = hit.dataCol();
                String txt = dc >= 0 && dc < row.size() && row.get(dc) != null ? row.get(dc) : "";
                onCellPicked.onPick(hit.dataRow(), dc, txt);
            }
        });

        Label cap = new Label("原稿");
        cap.setStyle("-fx-font-size:11px; -fx-text-fill:#5a7373; -fx-padding:4 8 0 8;");
        VBox box = new VBox(cap, scroll);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        box.setStyle("-fx-background-color:#eef5f5;");
        box.setMinWidth(280);
        return new OriginalPreview(box, highlight);
    }

    private static List<CropCell> cropCellsFor(List<CropCell> cells, int row, int col, boolean physical) {
        List<CropCell> out = new ArrayList<>();
        if (cells == null) {
            return out;
        }
        for (CropCell c : cells) {
            if (c != null && c.dataRow() == row && c.dataCol() == col && c.physical() == physical) {
                out.add(c);
            }
        }
        return out;
    }

    private static CropCell smallestCropCellAt(List<CropCell> cells, double px, double py) {
        CropCell bestOcr = null;
        CropCell bestPhys = null;
        double bestOcrArea = Double.POSITIVE_INFINITY;
        double bestPhysArea = Double.POSITIVE_INFINITY;
        for (CropCell c : cells) {
            if (c == null) {
                continue;
            }
            if (px < c.x() || px > c.x() + c.w() || py < c.y() || py > c.y() + c.h()) {
                continue;
            }
            double area = Math.max(1, c.w()) * Math.max(1, c.h());
            if (c.physical()) {
                if (area < bestPhysArea) {
                    bestPhysArea = area;
                    bestPhys = c;
                }
            } else if (area < bestOcrArea) {
                bestOcrArea = area;
                bestOcr = c;
            }
        }
        return bestOcr != null ? bestOcr : bestPhys;
    }

    private static void selectEditorCell(
            javafx.scene.control.TableView<javafx.collections.ObservableList<String>> table,
            int dataRow, int dataCol) {
        if (table == null || dataRow < 0 || dataRow >= table.getItems().size()) {
            return;
        }
        table.scrollTo(dataRow);
        table.getSelectionModel().clearSelection();
        int colIdx = dataCol + 1;
        if (colIdx >= 0 && colIdx < table.getColumns().size()) {
            table.getSelectionModel().select(dataRow, table.getColumns().get(colIdx));
            table.getFocusModel().focus(dataRow, table.getColumns().get(colIdx));
        } else {
            table.getSelectionModel().select(dataRow);
        }
    }

    /**
     * 把当前正在编辑的 TextField 内容写入 data。
     * JavaFX 的 {@code table.edit(-1, null)} 是取消编辑，不会提交，容易丢掉刚打的字。
     */
    private static void commitInProgressTableEdit(
            javafx.scene.control.TableView<javafx.collections.ObservableList<String>> table,
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            int colCount) {
        if (table == null || data == null) {
            return;
        }
        TablePosition<?, ?> pos = table.getEditingCell();
        TextField tf = null;
        if (table.getScene() != null) {
            tf = findTextField(table.getScene().getFocusOwner());
        }
        if (tf == null && pos != null) {
            // 焦点已跳到「确定」时，仍从正在编辑的单元格 graphic 取 TextField
            var col = pos.getTableColumn();
            if (col != null && pos.getRow() >= 0) {
                var cell = col.getCellFactory() != null
                        ? table.lookup(".table-cell:editing")
                        : null;
                if (cell != null) {
                    tf = findTextField(cell);
                }
            }
        }
        if (tf == null) {
            tf = findTextField(table);
        }
        if (pos != null && pos.getRow() >= 0 && pos.getRow() < data.size()
                && pos.getColumn() > 0 && tf != null) {
            int dataCol = pos.getColumn() - 1; // 0=勾选列
            if (dataCol >= 0 && dataCol < colCount) {
                javafx.collections.ObservableList<String> row = data.get(pos.getRow());
                while (row.size() <= dataCol) {
                    row.add("");
                }
                String v = tf.getText();
                if (v != null && "（空）".equals(v.trim())) {
                    v = "";
                }
                row.set(dataCol, v == null ? "" : v);
            }
        }
        table.edit(-1, null);
    }

    private static TextField findTextField(Node node) {
        if (node == null) {
            return null;
        }
        if (node instanceof TextField) {
            return (TextField) node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TextField found = findTextField(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<List<String>> parseMarkdownRows(String md) {
        List<List<String>> out = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return out;
        }
        for (String raw : md.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("###")) continue;
            if (isMarkdownSeparatorRow(line)) continue;
            if (!line.contains("|")) continue;
            if (line.startsWith("|")) line = line.substring(1);
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
            String[] parts = line.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (String p : parts) {
                cells.add(p.trim().replace("\\|", "|").replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n"));
            }
            if (!cells.isEmpty()) {
                out.add(cells);
            }
        }
        return out;
    }

    /** Markdown 表头下的 `| --- | --- |`；不含横线的全空数据行不要当分隔线丢掉。 */
    private static boolean isMarkdownSeparatorRow(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        return t.contains("|") && t.contains("-") && t.replaceAll("[|\\s\\-:]", "").isEmpty();
    }

    private static boolean looksLikeHeaderRow(List<String> row, String[] headers) {
        if (headers == null || headers.length == 0 || row == null) return false;
        int match = 0;
        int n = Math.min(row.size(), headers.length);
        for (int i = 0; i < n; i++) {
            String a = row.get(i) == null ? "" : row.get(i).trim();
            String b = headers[i] == null ? "" : headers[i].trim();
            if (!a.isEmpty() && a.equals(b)) match++;
        }
        return match >= Math.max(1, headers.length / 2);
    }

    public static void show(String slotDefLabel, List<CellRect> cells,
                            List<OcrRow> ocrRows, int rx, int ry) {
        String[] cols = new String[0];
        String tableName = "表格";
        if (slotDefLabel != null) {
            if (slotDefLabel.startsWith("*")) {
                int colon = slotDefLabel.indexOf(':');
                if (colon > 1) {
                    tableName = slotDefLabel.substring(1, colon).trim();
                    String rest = slotDefLabel.substring(colon + 1).trim();
                    int eq = rest.indexOf('=');
                    String colsStr;
                    if (eq >= 0) {
                        colsStr = rest.substring(0, eq).trim();
                    } else {
                        colsStr = rest;
                    }
                    String[] rawCols = colsStr.isEmpty() ? new String[0] : colsStr.split(",");
                    List<String> finalCols = new ArrayList<>();
                    for (String raw : rawCols) {
                        String trimmed = raw.trim();
                        if (!trimmed.isEmpty()) finalCols.add(trimmed);
                    }
                    cols = finalCols.toArray(new String[0]);
                } else {
                    tableName = slotDefLabel.substring(1).trim();
                }
            } else {
                tableName = slotDefLabel;
            }
        }
        final String[] headerCols = cols;
        final String popupTitle = tableName;

        int maxRow;
        final int maxCol;
        String[][] grid;
        final String modeLabel;

        if (headerCols.length > 0) {
            boolean useCells = cells != null && !cells.isEmpty()
                    && cells.stream().anyMatch(c -> c.getRow() >= 0 && c.getCol() >= 0);
            if (useCells) {
                maxCol = headerCols.length;
                int maxCellRow = 0;
                for (CellRect cell : cells) {
                    if (cell.getRow() > maxCellRow) maxCellRow = cell.getRow();
                }
                maxRow = maxCellRow + 1;
                if (maxRow < 2) maxRow = 2;
                grid = new String[maxRow][maxCol];
                for (int r = 0; r < maxRow; r++) {
                    for (int c = 0; c < maxCol; c++) {
                        grid[r][c] = "";
                    }
                }
                for (int c = 0; c < headerCols.length; c++) {
                    grid[0][c] = headerCols[c].trim();
                }
                Set<String> headerSet = new HashSet<>();
                for (String h : headerCols) { String n = h.trim(); if (!n.isEmpty()) headerSet.add(n); }
                List<CellRect> dataCells = new ArrayList<>();
                for (CellRect cell : cells) {
                    String t = cell.getText() != null ? cell.getText().trim() : "";
                    if (cell.getRowspan() == 1 && !t.isEmpty() && headerSet.contains(t)) continue;
                    dataCells.add(cell);
                }
                int cellRows = dataCells.stream().mapToInt(CellRect::getRow).max().orElse(-1) + 1;
                boolean isGrid = cellRows >= 2;
                int cellRowMin = dataCells.stream().mapToInt(CellRect::getRow).min().orElse(0);
                int cellRowMax = dataCells.stream().mapToInt(CellRect::getRow).max().orElse(0);
                int cellColMin = dataCells.stream().mapToInt(CellRect::getCol).min().orElse(0);
                int cellColMax = dataCells.stream().mapToInt(CellRect::getCol).max().orElse(0);
                List<CellRect> coreCellsForMargin = new ArrayList<>();
                for (CellRect cell : dataCells) {
                    int cr = cell.getRow();
                    if ((cr == cellRowMin || cr == cellRowMax)
                            && (cell.getText() == null || cell.getText().trim().isEmpty())) {
                        continue;
                    }
                    coreCellsForMargin.add(cell);
                }
                int[] marginOff = refineMarginOffsets(
                        dataCells, resolveMarginOffsets(dataCells, coreCellsForMargin), maxCol);
                boolean hasTopMargin = marginOff[0] == 1;
                boolean hasLeftMargin = marginOff[1] == 1;

                if (isGrid) {
                    maxRow = cellRows + 1;
                    grid = new String[maxRow][maxCol];
                    for (int r = 0; r < maxRow; r++) {
                        for (int c = 0; c < maxCol; c++) {
                            grid[r][c] = "";
                        }
                    }
                    for (int c = 0; c < headerCols.length; c++) {
                        grid[0][c] = headerCols[c].trim();
                    }
                    List<CellRect> coreCells = coreCellsForMargin;
                    fillGridByOcrPairing(
                            popupTitle, grid, maxRow, maxCol, headerCols,
                            ocrRows, cells, dataCells, coreCells,
                            hasTopMargin, hasLeftMargin, null);
                    modeLabel = "OpenCV网格 | 列头: " + String.join(",", headerCols);
                } else {
                    List<CellRect> coreCells = new ArrayList<>();
                    for (CellRect cell : dataCells) {
                        int cr = cell.getRow();
                        if ((cr == cellRowMin || cr == cellRowMax)
                                && (cell.getText() == null || cell.getText().trim().isEmpty())) {
                            continue;
                        }
                        coreCells.add(cell);
                    }
                    Map<Integer, List<String>> colLabelsMap = new LinkedHashMap<>();
                    Map<Integer, List<String>> colValuesMap = new LinkedHashMap<>();
                    for (OcrRow ocr : ocrRows) {
                        String text = ocr.getFeature() != null ? ocr.getFeature().trim() : "";
                        if (text.isEmpty()) continue;
                        CellRect bestCell = null;
                        double bestOverlap = 0;
                        for (CellRect cell : coreCells) {
                            double ox = Math.max(ocr.getStartX(), cell.getX());
                            double oy = Math.max(ocr.getStartY(), cell.getY());
                            double ex = Math.min(ocr.getEndX(), cell.getEndX());
                            double ey = Math.min(ocr.getEndY(), cell.getEndY());
                            if (ex > ox && ey > oy) {
                                double overlap = (ex - ox) * (ey - oy);
                                if (overlap > bestOverlap) {
                                    bestOverlap = overlap;
                                    bestCell = cell;
                                }
                            }
                        }
                        if (bestCell != null) {
                            int col = bestCell.getCol();
                            if (hasLeftMargin) col--;
                            if (col < 0 || col >= maxCol) continue;
                            if (bestCell.getRowspan() >= 2) {
                                colLabelsMap.computeIfAbsent(col, k -> new ArrayList<>()).add(text);
                            } else {
                                colValuesMap.computeIfAbsent(col, k -> new ArrayList<>()).add(text);
                            }
                        }
                    }
                    String[] colLabels = new String[maxCol];
                    String[] colValues = new String[maxCol];
                    for (int c = 0; c < maxCol; c++) { colLabels[c] = ""; colValues[c] = ""; }
                    for (Map.Entry<Integer, List<String>> e : colLabelsMap.entrySet()) {
                        int col = e.getKey();
                        if (col >= 0 && col < maxCol) {
                            colLabels[col] = String.join(" ", e.getValue());
                        }
                    }
                    for (Map.Entry<Integer, List<String>> e : colValuesMap.entrySet()) {
                        int col = e.getKey();
                        if (col >= 0 && col < maxCol) {
                            colValues[col] = String.join("\n", e.getValue());
                        }
                    }
                    int dr = 1;
                    boolean hasLabels = false, hasValues = false;
                    for (int c = 0; c < maxCol; c++) {
                        if (!colLabels[c].isEmpty()) { grid[dr][c] = colLabels[c]; hasLabels = true; }
                    }
                    if (hasLabels) dr++;
                    for (int c = 0; c < maxCol; c++) {
                        if (!colValues[c].isEmpty()) { grid[dr][c] = colValues[c]; hasValues = true; }
                    }
                    maxRow = 1 + (hasLabels ? 1 : 0) + (hasValues ? 1 : 0);
                    modeLabel = "OCR格子(label-value) | 列头: " + String.join(",", headerCols);
                }
                // 保留全空行，保证弹窗行序与物理格一致（点行高亮才对得上）
            } else {
                List<OcrRow> cleanedRows = OcrUtils.removeVerticalLabelFragments(ocrRows, headerCols);
                var result = TableRecognizer.recognize(cleanedRows, headerCols, false);
                int dataStart = 0;
                if (!result.rows.isEmpty()) {
                    String[] first = result.rows.get(0);
                    int match = 0;
                    for (int c = 0; c < first.length; c++) {
                        if (first[c] == null || first[c].isEmpty()) continue;
                        for (int hc = 0; hc < headerCols.length; hc++) {
                            String h = headerCols[hc].trim();
                            if (first[c].equals(h) || h.contains(first[c]) || first[c].contains(h)
                                    || OcrUtils.levenshtein(first[c], h) <= 2) {
                                match++;
                                break;
                            }
                        }
                    }
                    if (match >= 2 && match >= headerCols.length / 2) {
                        dataStart = 1;
                    }
                }
                int dataRows = result.rows.size() - dataStart;
                maxRow = dataRows + 1;
                maxCol = Math.max(headerCols.length, result.colCount);
                grid = new String[maxRow][maxCol];
                for (int c = 0; c < maxCol; c++) {
                    grid[0][c] = c < headerCols.length ? headerCols[c].trim() : "Col" + (c + 1);
                }
                for (int r = dataStart; r < result.rows.size(); r++) {
                    String[] row = result.rows.get(r);
                    int dr = r - dataStart + 1;
                    for (int c = 0; c < maxCol; c++) {
                        grid[dr][c] = (c < row.length && row[c] != null) ? row[c] : "";
                    }
                }
                modeLabel = "坐标聚类 | 列头: " + String.join(",", headerCols);
            }
        } else {
            var result = TableRecognizer.recognize(ocrRows, null, false);
            maxRow = result.rows.size() + 1;
            maxCol = result.colCount;
            grid = new String[maxRow][maxCol];
            for (int c = 0; c < maxCol; c++) {
                grid[0][c] = result.columnHeaders.length > c ? result.columnHeaders[c] : "Col" + (c + 1);
            }
            for (int r = 0; r < result.rows.size(); r++) {
                String[] row = result.rows.get(r);
                for (int c = 0; c < maxCol; c++) {
                    grid[r + 1][c] = (c < row.length && row[c] != null) ? row[c] : "";
                }
            }
            modeLabel = "坐标聚类(自动)";
        }

        // build popup — Markdown → HTML → WebView
        Stage popup = new Stage();
        popup.setTitle(popupTitle + " 校验结果 @" + rx + "," + ry
                + " [" + modeLabel + "]");

        final String md = MarkdownUtils.gridToMarkdown(grid, maxRow, maxCol, popupTitle);
        final String html = MarkdownUtils.markdownToHtml(md);

        WebView webView = new WebView();
        webView.setPrefWidth(800);
        webView.setPrefHeight(500);
        // loadContent 无法可靠加载脚本；写到临时目录用 file:// 加载本地 KaTeX
        try {
            java.nio.file.Path index = MarkdownUtils.writePopupHtml(html);
            var engine = webView.getEngine();
            engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                    try {
                        engine.executeScript(
                                "if(typeof renderMathInElement==='function'){"
                                        + "renderMathInElement(document.body,{"
                                        + "delimiters:["
                                        + "{left:'$$',right:'$$',display:true},"
                                        + "{left:'$',right:'$',display:false}"
                                        + "],throwOnError:false});}");
                    } catch (Exception ignored) {
                    }
                }
            });
            engine.load(index.toUri().toString());
        } catch (Exception ex) {
            webView.getEngine().loadContent(html);
        }

        ScrollPane sp = new ScrollPane(webView);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);

        Button copyMdBtn = new Button("📋 复制 Markdown");
        copyMdBtn.setStyle("-fx-font-size:11px; -fx-padding:4 12; -fx-cursor:hand;");
        copyMdBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(md);
            Clipboard.getSystemClipboard().setContent(content);
            copyMdBtn.setText("✅ 已复制!");
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> copyMdBtn.setText("📋 复制 Markdown"));
            }).start();
        });

        Label hintLbl = new Label("  " + maxRow + "行 × " + maxCol + "列  |  " + modeLabel);
        hintLbl.setStyle("-fx-font-size:10px; -fx-text-fill:#7a9494; -fx-padding:4 0 0 0;");

        HBox btnBar = new HBox(8, copyMdBtn, hintLbl);
        btnBar.setPadding(new javafx.geometry.Insets(4, 10, 8, 10));
        btnBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox root = new VBox(sp, btnBar);
        root.setStyle("-fx-background-color:#eef5f5;");
        VBox.setVgrow(sp, javafx.scene.layout.Priority.ALWAYS);

        double viewW = Math.max(600, Math.min(1400, maxCol * 100 + 40));
        double viewH = Math.max(300, Math.min(900, maxRow * 30 + 60));
        Scene scene = new Scene(root, viewW, viewH);
        popup.setScene(scene);
        popup.setResizable(true);
        popup.show();
    }

    /**
     * 边距判定：只有「空边框格在更外、有内容的 core 格更靠内」时才算边距。
     * 旧逻辑 cellMin==0 就减 1，会把真正落在 (0,0) 的左上角 OCR 直接 DROP。
     *
     * @return int[2]{hasTopMargin ? 1 : 0, hasLeftMargin ? 1 : 0}
     */
    public static int[] resolveMarginOffsets(List<CellRect> dataCells, List<CellRect> coreCells) {
        if (dataCells == null || dataCells.isEmpty()) {
            return new int[] {0, 0};
        }
        int cellRowMin = dataCells.stream().mapToInt(CellRect::getRow).min().orElse(0);
        int cellColMin = dataCells.stream().mapToInt(CellRect::getCol).min().orElse(0);
        List<CellRect> core = (coreCells == null || coreCells.isEmpty())
                ? filterCoreCells(dataCells,
                cellRowMin,
                dataCells.stream().mapToInt(CellRect::getRow).max().orElse(0),
                cellColMin,
                dataCells.stream().mapToInt(CellRect::getCol).max().orElse(0))
                : coreCells;
        if (core.isEmpty()) {
            return new int[] {0, 0};
        }
        int coreRowMin = core.stream().mapToInt(CellRect::getRow).min().orElse(cellRowMin);
        int coreColMin = core.stream().mapToInt(CellRect::getCol).min().orElse(cellColMin);
        int top = coreRowMin > cellRowMin ? 1 : 0;
        int left = coreColMin > cellColMin ? 1 : 0;
        return new int[] {top, left};
    }

    /**
     * 物理列比表头多一列、且最左列几乎空时，强制当作左边距。
     * 避免左边距格被噪点 OCR 写进 text 后 {@link #resolveMarginOffsets} 判不成边距，
     * 工序号又被特判进第 0 列 → 后面整列右偏（工序名称进设备.名称等）。
     */
    public static int[] refineMarginOffsets(
            List<CellRect> dataCells, int[] margin, int headerColCount) {
        int top = margin != null && margin.length > 0 ? margin[0] : 0;
        int left = margin != null && margin.length > 1 ? margin[1] : 0;
        if (dataCells == null || dataCells.isEmpty() || headerColCount <= 0) {
            return new int[] {top, left};
        }
        int cellColMin = dataCells.stream().mapToInt(CellRect::getCol).min().orElse(0);
        int cellColMax = dataCells.stream().mapToInt(CellRect::getCol).max().orElse(0);
        int physCols = cellColMax - cellColMin + 1;
        if (left == 0 && physCols == headerColCount + 1
                && isMostlyEmptyColumn(dataCells, cellColMin)) {
            left = 1;
        }
        int cellRowMin = dataCells.stream().mapToInt(CellRect::getRow).min().orElse(0);
        int cellRowMax = dataCells.stream().mapToInt(CellRect::getRow).max().orElse(0);
        int physRows = cellRowMax - cellRowMin + 1;
        // 顶边距：物理行比「表头+至少1数据行」多出的空顶行（粗判）
        if (top == 0 && physRows >= 3 && isMostlyEmptyRow(dataCells, cellRowMin)) {
            top = 1;
        }
        return new int[] {top, left};
    }

    static boolean isMostlyEmptyColumn(List<CellRect> cells, int col) {
        int n = 0;
        int empty = 0;
        for (CellRect cell : cells) {
            if (cell == null || cell.getCol() != col) {
                continue;
            }
            n++;
            String t = cell.getText() != null ? cell.getText().trim() : "";
            if (t.isEmpty()) {
                empty++;
            }
        }
        return n > 0 && empty * 5 >= n * 4; // ≥80% 空
    }

    static boolean isMostlyEmptyRow(List<CellRect> cells, int row) {
        int n = 0;
        int empty = 0;
        for (CellRect cell : cells) {
            if (cell == null || cell.getRow() != row) {
                continue;
            }
            n++;
            String t = cell.getText() != null ? cell.getText().trim() : "";
            if (t.isEmpty()) {
                empty++;
            }
        }
        return n > 0 && empty * 5 >= n * 4;
    }

    private static List<CellRect> filterCoreCells(
            List<CellRect> dataCells,
            int cellRowMin, int cellRowMax, int cellColMin, int cellColMax) {
        List<CellRect> coreCells = new ArrayList<>();
        for (CellRect cell : dataCells) {
            int cr = cell.getRow();
            // 只丢掉顶/底空边框；左右空格常常是工序号列（CellRect.text 未填 OCR）
            if ((cr == cellRowMin || cr == cellRowMax)
                    && (cell.getText() == null || cell.getText().trim().isEmpty())) {
                continue;
            }
            coreCells.add(cell);
        }
        return coreCells;
    }

    /**
     * OCR ↔ 物理格配对写入 grid。
     * 短序号（1/4/5）易被大格用「绝对重叠面积」吸走，故：中心在格内优先、按 OCR 覆盖率，
     * 序号类再偏好更窄、更靠左的格。
     */
    private static void fillGridByOcrPairing(
            String tableName,
            String[][] grid,
            int maxRow,
            int maxCol,
            String[] headerCols,
            List<OcrRow> ocrRows,
            List<CellRect> allCells,
            List<CellRect> dataCells,
            List<CellRect> coreCells,
            boolean hasTopMargin,
            boolean hasLeftMargin,
            List<CropCell> hitsOut) {
        if (ocrRows == null || coreCells == null) {
            return;
        }
        // 仅表左侧约 22% 宽当作「工步号区」；右侧短数字（如「4」）不再被左偏吸走
        double leftZoneX = leftStepNoZoneX(coreCells);
        for (OcrRow ocr : ocrRows) {
            if (ocr == null) {
                continue;
            }
            String text = ocr.getFeature() != null ? ocr.getFeature().trim() : "";
            if (text.isEmpty()) {
                continue;
            }
            double ocrCx = (ocr.getStartX() + ocr.getEndX()) / 2.0;
            boolean inLeftStepZone = ocrCx <= leftZoneX;
            CellPick best = pickBestCellForOcr(ocr, text, coreCells, inLeftStepZone);
            if (best == null) {
                continue;
            }
            CellRect bestCell = best.cell();
            int cr = bestCell.getRow();
            int cc = bestCell.getCol();
            if (hasTopMargin) {
                cr--;
            }
            if (hasLeftMargin) {
                cc--;
            }
            cc = skipSparseCodeHeaderColumn(cc, headerCols, dataPhysColCount(coreCells, hasLeftMargin));
            if (cr < 0 || cc < 0 || cc >= maxCol) {
                continue;
            }
            int gridRow = cr + 1;
            if (gridRow < 0 || gridRow >= maxRow) {
                continue;
            }
            String exist = grid[gridRow][cc];
            if (exist != null && !exist.isEmpty()
                    && com.weavelay.ocr.FormulaMixedComposer.isNearDuplicateOf(exist, text)) {
                continue;
            }
            grid[gridRow][cc] = (exist == null || exist.isEmpty()) ? text : exist + "\n" + text;
            if (hitsOut != null && ocr.hasBbox()) {
                hitsOut.add(new CropCell(
                        gridRow - 1,
                        cc,
                        ocr.getStartX(),
                        ocr.getStartY(),
                        ocr.getEndX() - ocr.getStartX(),
                        ocr.getEndY() - ocr.getStartY(),
                        false));
            }
        }
        if (hitsOut != null && dataCells != null) {
            int physCols = dataPhysColCount(coreCells, hasLeftMargin);
            for (CellRect cell : dataCells) {
                if (cell == null || cell.getWidth() <= 1 || cell.getHeight() <= 1) {
                    continue;
                }
                int cr = cell.getRow();
                int cc = cell.getCol();
                if (hasTopMargin) {
                    cr--;
                }
                if (hasLeftMargin) {
                    cc--;
                }
                cc = skipSparseCodeHeaderColumn(cc, headerCols, physCols);
                if (cr < 0 || cc < 0 || cc >= maxCol) {
                    continue;
                }
                int gridRow = cr + 1;
                if (gridRow < 1 || gridRow >= maxRow) {
                    continue;
                }
                hitsOut.add(new CropCell(
                        gridRow - 1,
                        cc,
                        cell.getX(),
                        cell.getY(),
                        cell.getWidth(),
                        cell.getHeight(),
                        true));
            }
        }
        realignSparseCodeColumn(grid, maxRow, maxCol, headerCols, hitsOut);
    }

    private record CellPick(CellRect cell, double score, boolean centerIn, double cover) {
    }

    /** 工步号/序号：短数字或 1Y/2Y/F1 等。 */
    static boolean looksLikeStepNo(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty() || t.length() > 6) {
            return false;
        }
        return t.matches("^[0-9]+[YyWwFf]?$") || t.matches("^[YyWwFf][0-9]+$");
    }

    static boolean isSparseCodeHeader(String header) {
        if (header == null) {
            return false;
        }
        String t = header.trim();
        return "特性代号".equals(t) || "材料代号".equals(t) || "代号".equals(t);
    }

    static int dataPhysColCount(List<CellRect> coreCells, boolean hasLeftMargin) {
        if (coreCells == null || coreCells.isEmpty()) {
            return 0;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (CellRect cell : coreCells) {
            if (cell == null) {
                continue;
            }
            min = Math.min(min, cell.getCol());
            max = Math.max(max, cell.getCol());
        }
        if (min > max) {
            return 0;
        }
        int n = max - min + 1;
        return hasLeftMargin ? Math.max(0, n - 1) : n;
    }

    /**
     * 纸面没有「特性代号」列时：不要只把工序号拽回第 0 列。
     * 整表一起左移；若第 0 列已经是工序号、第 1 列却是工序名称，则在特性代号处插空列、后面整列右移。
     */
    static void realignSparseCodeColumn(
            String[][] grid, int maxRow, int maxCol, String[] headerCols, List<CropCell> hitsOut) {
        if (grid == null || headerCols == null || headerCols.length < 3 || maxRow < 2 || maxCol < 3) {
            return;
        }
        if (!isSparseCodeHeader(headerCols[1])) {
            return;
        }
        int dataRows = 0;
        int filled0 = 0;
        int filled1 = 0;
        int step0 = 0;
        int step1 = 0;
        int name1 = 0;
        for (int r = 1; r < maxRow; r++) {
            String c0 = cellText(grid, r, 0);
            String c1 = cellText(grid, r, 1);
            if (c0.isEmpty() && c1.isEmpty()) {
                continue;
            }
            dataRows++;
            if (!c0.isEmpty()) {
                filled0++;
            }
            if (!c1.isEmpty()) {
                filled1++;
            }
            if (looksLikeStepNo(c0)) {
                step0++;
            }
            if (looksLikeStepNo(c1)) {
                step1++;
            }
            if (looksLikeProcessName(c1)) {
                name1++;
            }
        }
        if (dataRows < 2) {
            return;
        }
        int dataH = maxRow - 1;
        if (step0 == 0 && filled0 * 2 <= dataRows && step1 >= 2) {
            shiftGridLeft(grid, 1, maxRow, maxCol);
            shiftHits(hitsOut, -1, 0, dataH, maxCol, null, null);
        }
        step0 = 0;
        name1 = 0;
        filled0 = 0;
        dataRows = 0;
        for (int r = 1; r < maxRow; r++) {
            String c0 = cellText(grid, r, 0);
            String c1 = cellText(grid, r, 1);
            if (c0.isEmpty() && c1.isEmpty()) {
                continue;
            }
            dataRows++;
            if (!c0.isEmpty()) {
                filled0++;
            }
            if (looksLikeStepNo(c0)) {
                step0++;
            }
            if (looksLikeProcessName(c1)) {
                name1++;
            }
        }
        if (dataRows >= 2 && step0 >= 2 && name1 >= 2) {
            insertEmptyColumn(grid, 1, maxRow, maxCol, 1);
            bumpHitColsFrom(hitsOut, 1);
        }
    }

    static boolean looksLikeProcessName(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.isEmpty() || looksLikeStepNo(t)) {
            return false;
        }
        return t.length() >= 2;
    }

    private static String cellText(String[][] grid, int r, int c) {
        if (r < 0 || c < 0 || r >= grid.length || grid[r] == null || c >= grid[r].length) {
            return "";
        }
        return grid[r][c] == null ? "" : grid[r][c].trim();
    }

    static void shiftGridLeft(String[][] grid, int fromRow, int maxRow, int maxCol) {
        for (int r = fromRow; r < maxRow; r++) {
            if (grid[r] == null) {
                continue;
            }
            for (int c = 0; c < maxCol - 1; c++) {
                grid[r][c] = c + 1 < grid[r].length ? grid[r][c + 1] : "";
            }
            if (maxCol - 1 < grid[r].length) {
                grid[r][maxCol - 1] = "";
            }
        }
    }

    /** 从 fromCol 起整列右移一格，fromCol 变空（插入空的特性代号）。 */
    static void insertEmptyColumn(String[][] grid, int fromRow, int maxRow, int maxCol, int fromCol) {
        for (int r = fromRow; r < maxRow; r++) {
            if (grid[r] == null) {
                continue;
            }
            for (int c = maxCol - 1; c > fromCol; c--) {
                String src = c - 1 < grid[r].length ? grid[r][c - 1] : "";
                if (c < grid[r].length) {
                    grid[r][c] = src;
                }
            }
            if (fromCol < grid[r].length) {
                grid[r][fromCol] = "";
            }
        }
    }

    static void bumpHitColsFrom(List<CropCell> hits, int fromCol) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<CropCell> next = new ArrayList<>();
        for (CropCell h : hits) {
            if (h == null) {
                continue;
            }
            int c = h.dataCol();
            if (c >= fromCol) {
                c++;
            }
            next.add(h.at(h.dataRow(), c));
        }
        hits.clear();
        hits.addAll(next);
    }

    static int skipSparseCodeHeaderColumn(int logicalCol, String[] headerCols, int dataPhysCols) {
        if (logicalCol < 1 || headerCols == null || headerCols.length < 3) {
            return logicalCol;
        }
        if (!isSparseCodeHeader(headerCols[1])) {
            return logicalCol;
        }
        if (dataPhysCols == headerCols.length - 1) {
            return logicalCol + 1;
        }
        return logicalCol;
    }

    /** 工步号区右边界：表芯最左 22% 宽（避免右侧短数字被当序号吸走）。 */
    private static double leftStepNoZoneX(List<CellRect> coreCells) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        for (CellRect cell : coreCells) {
            if (cell == null) {
                continue;
            }
            minX = Math.min(minX, cell.getX());
            maxX = Math.max(maxX, cell.getEndX());
        }
        if (!Double.isFinite(minX) || maxX <= minX) {
            return Double.POSITIVE_INFINITY;
        }
        return minX + (maxX - minX) * 0.22;
    }

    /**
     * 选格：中心在格内 ≫ OCR 覆盖率；仅左侧工步号区才偏好窄格/左格。
     */
    private static CellPick pickBestCellForOcr(
            OcrRow ocr, String text, List<CellRect> coreCells, boolean inLeftStepZone) {
        if (ocr == null || coreCells == null || coreCells.isEmpty()) {
            return null;
        }
        double ow = ocr.getEndX() - ocr.getStartX();
        double oh = ocr.getEndY() - ocr.getStartY();
        if (ow <= 0 || oh <= 0) {
            return null;
        }
        double ocrArea = ow * oh;
        double cx = (ocr.getStartX() + ocr.getEndX()) / 2.0;
        double cy = (ocr.getStartY() + ocr.getEndY()) / 2.0;
        boolean seqLike = inLeftStepZone && looksLikeStepNo(text);
        CellPick best = null;
        for (CellRect cell : coreCells) {
            double ox = Math.max(ocr.getStartX(), cell.getX());
            double oy = Math.max(ocr.getStartY(), cell.getY());
            double ex = Math.min(ocr.getEndX(), cell.getEndX());
            double ey = Math.min(ocr.getEndY(), cell.getEndY());
            double iw = ex - ox;
            double ih = ey - oy;
            if (iw <= 0 || ih <= 0) {
                continue;
            }
            boolean centerIn = cx >= cell.getX() && cx <= cell.getEndX()
                    && cy >= cell.getY() && cy <= cell.getEndY();
            double cover = (iw * ih) / ocrArea;
            double minCover = seqLike ? 0.25 : 0.45;
            if (!centerIn && cover < minCover) {
                continue;
            }
            double score = 0;
            if (centerIn) {
                score += 1000;
            }
            score += cover * 200;
            if (seqLike) {
                // 工序号落在「窄特性代号格」时，/宽度 会把 1Y 吸进第 2 列；改成明显偏左
                score += 400.0 / Math.max(cell.getCol() + 1, 1);
                double cw = Math.max(cell.getEndX() - cell.getX(), 1);
                if (cw > 180) {
                    score -= 80;
                }
            } else {
                score += Math.min(iw * ih, 2000) * 0.005;
            }
            if (best == null || score > best.score()) {
                best = new CellPick(cell, score, centerIn, cover);
            }
        }
        return best;
    }

    private static Button moveArrowBtn(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button b = new Button(text);
        b.setFocusTraversable(false);
        b.setMinWidth(36);
        b.setOnAction(handler);
        return b;
    }

    static void ensureRowWidth(
            javafx.collections.ObservableList<String> row, int colCount) {
        if (row == null) {
            return;
        }
        while (row.size() < colCount) {
            row.add("");
        }
    }

    static void shiftAll(
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            int colCount, int dx, int dy) {
        if (data == null || data.isEmpty() || colCount <= 0) {
            return;
        }
        if (dx != 0) {
            for (int r = 0; r < data.size(); r++) {
                shiftRowHorizontal(data, colCount, r, dx);
            }
        }
        if (dy != 0) {
            int n = data.size();
            if (dy < 0) {
                for (int r = 0; r < n - 1; r++) {
                    copyRowValues(data.get(r), data.get(r + 1), colCount);
                }
                clearRowValues(data.get(n - 1), colCount);
            } else {
                for (int r = n - 1; r > 0; r--) {
                    copyRowValues(data.get(r), data.get(r - 1), colCount);
                }
                clearRowValues(data.get(0), colCount);
            }
        }
    }

    static void shiftRowHorizontal(
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            int colCount, int row, int dx) {
        if (data == null || row < 0 || row >= data.size() || colCount <= 0 || dx == 0) {
            return;
        }
        javafx.collections.ObservableList<String> line = data.get(row);
        ensureRowWidth(line, colCount);
        if (dx < 0) {
            for (int c = 0; c < colCount - 1; c++) {
                line.set(c, line.get(c + 1));
            }
            line.set(colCount - 1, "");
        } else {
            for (int c = colCount - 1; c > 0; c--) {
                line.set(c, line.get(c - 1));
            }
            line.set(0, "");
        }
    }

    static void shiftColVertical(
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            int colCount, int col, int dy) {
        if (data == null || data.isEmpty() || col < 0 || col >= colCount || dy == 0) {
            return;
        }
        int n = data.size();
        if (dy < 0) {
            for (int r = 0; r < n - 1; r++) {
                ensureRowWidth(data.get(r), colCount);
                ensureRowWidth(data.get(r + 1), colCount);
                data.get(r).set(col, data.get(r + 1).get(col));
            }
            ensureRowWidth(data.get(n - 1), colCount);
            data.get(n - 1).set(col, "");
        } else {
            for (int r = n - 1; r > 0; r--) {
                ensureRowWidth(data.get(r), colCount);
                ensureRowWidth(data.get(r - 1), colCount);
                data.get(r).set(col, data.get(r - 1).get(col));
            }
            ensureRowWidth(data.get(0), colCount);
            data.get(0).set(col, "");
        }
    }

    static boolean swapRows(
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            javafx.collections.ObservableList<Boolean> rowChecked,
            int a, int b) {
        if (data == null || a < 0 || b < 0 || a >= data.size() || b >= data.size() || a == b) {
            return false;
        }
        javafx.collections.ObservableList<String> ra = data.get(a);
        data.set(a, data.get(b));
        data.set(b, ra);
        if (rowChecked != null && a < rowChecked.size() && b < rowChecked.size()) {
            Boolean ca = rowChecked.get(a);
            rowChecked.set(a, rowChecked.get(b));
            rowChecked.set(b, ca);
        }
        return true;
    }

    static boolean swapCols(
            javafx.collections.ObservableList<javafx.collections.ObservableList<String>> data,
            int colCount, int a, int b) {
        if (data == null || a < 0 || b < 0 || a >= colCount || b >= colCount || a == b) {
            return false;
        }
        for (javafx.collections.ObservableList<String> row : data) {
            ensureRowWidth(row, colCount);
            String t = row.get(a);
            row.set(a, row.get(b));
            row.set(b, t);
        }
        return true;
    }

    private static void copyRowValues(
            javafx.collections.ObservableList<String> dest,
            javafx.collections.ObservableList<String> src,
            int colCount) {
        ensureRowWidth(dest, colCount);
        ensureRowWidth(src, colCount);
        for (int c = 0; c < colCount; c++) {
            dest.set(c, src.get(c));
        }
    }

    private static void clearRowValues(
            javafx.collections.ObservableList<String> row, int colCount) {
        ensureRowWidth(row, colCount);
        for (int c = 0; c < colCount; c++) {
            row.set(c, "");
        }
    }

    static void shiftHits(
            List<CropCell> hits, int dx, int dy, int rows, int cols,
            Integer onlyRow, Integer onlyCol) {
        if (hits == null || hits.isEmpty() || (dx == 0 && dy == 0)) {
            return;
        }
        List<CropCell> next = new ArrayList<>();
        for (CropCell h : hits) {
            if (h == null) {
                continue;
            }
            if (onlyRow != null && h.dataRow() != onlyRow) {
                next.add(h);
                continue;
            }
            if (onlyCol != null && h.dataCol() != onlyCol) {
                next.add(h);
                continue;
            }
            int nr = h.dataRow() + dy;
            int nc = h.dataCol() + dx;
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                next.add(h.at(nr, nc));
            }
        }
        hits.clear();
        hits.addAll(next);
    }

    static void swapHitRows(List<CropCell> hits, int a, int b) {
        if (hits == null || a == b) {
            return;
        }
        List<CropCell> next = new ArrayList<>();
        for (CropCell h : hits) {
            if (h == null) {
                continue;
            }
            int r = h.dataRow();
            if (r == a) {
                r = b;
            } else if (r == b) {
                r = a;
            }
            next.add(h.at(r, h.dataCol()));
        }
        hits.clear();
        hits.addAll(next);
    }

    static void swapHitCols(List<CropCell> hits, int a, int b) {
        if (hits == null || a == b) {
            return;
        }
        List<CropCell> next = new ArrayList<>();
        for (CropCell h : hits) {
            if (h == null) {
                continue;
            }
            int c = h.dataCol();
            if (c == a) {
                c = b;
            } else if (c == b) {
                c = a;
            }
            next.add(h.at(h.dataRow(), c));
        }
        hits.clear();
        hits.addAll(next);
    }
}
