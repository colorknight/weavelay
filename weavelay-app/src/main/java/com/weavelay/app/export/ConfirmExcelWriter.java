package com.weavelay.app.export;

import com.weavelay.core.formula.LatexToPlainText;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.store.TemplateRule;
import javafx.scene.control.TreeItem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 确认写出 xlsx。
 * <p>若文件类型配置了输出模板：首次从模板复制输出文件，之后在同一文件上按
 * {@code {{占位符}}} 渐进填充（目录可克隆子 Sheet）；否则沿用「第N页」简易 Sheet。
 */
public final class ConfirmExcelWriter {

    private static final Pattern MD_SEP = Pattern.compile("^\\s*\\|?\\s*[-:]+(\\s*\\|\\s*[-:]+)*\\s*\\|?\\s*$");

    static {
        TemplateExcelFiller.relaxZipBombLimit();
    }

    private ConfirmExcelWriter() {}

    /**
     * @param templatePath 可为 null / 不存在 → 走无模板简易写出
     * @param pageNum      1-based（无模板时作 Sheet 名）
     */
    public static void writePage(
            Path xlsxPath,
            Path templatePath,
            int pageNum,
            String pageKindName,
            List<TreeItem<SlotValue>> topSlots) throws IOException {
        writePage(xlsxPath, templatePath, pageNum, pageKindName, topSlots, List.of());
    }

    public static void writePage(
            Path xlsxPath,
            Path templatePath,
            int pageNum,
            String pageKindName,
            List<TreeItem<SlotValue>> topSlots,
            List<TemplateRule> templateRules) throws IOException {
        if (templatePath != null && Files.isRegularFile(templatePath)) {
            TemplateExcelFiller.ensureOutputFromTemplate(templatePath, xlsxPath);
            TemplateExcelFiller.fill(xlsxPath, templatePath, topSlots, templateRules);
            return;
        }
        writeLegacyPage(xlsxPath, pageNum, pageKindName, topSlots);
    }

    /** 兼容旧调用。 */
    public static void writePage(
            Path xlsxPath,
            int pageNum,
            String pageKindName,
            List<TreeItem<SlotValue>> topSlots) throws IOException {
        writePage(xlsxPath, null, pageNum, pageKindName, topSlots);
    }

    private static void writeLegacyPage(
            Path xlsxPath,
            int pageNum,
            String pageKindName,
            List<TreeItem<SlotValue>> topSlots) throws IOException {
        Workbook wb;
        if (Files.isRegularFile(xlsxPath)) {
            try (InputStream in = Files.newInputStream(xlsxPath)) {
                wb = new XSSFWorkbook(in);
            }
        } else {
            Files.createDirectories(xlsxPath.getParent());
            wb = new XSSFWorkbook();
        }
        try {
            String sheetName = "第" + pageNum + "页";
            Sheet sheet = wb.getSheet(sheetName);
            if (sheet != null) {
                int idx = wb.getSheetIndex(sheet);
                wb.removeSheetAt(idx);
            }
            sheet = wb.createSheet(sheetName);
            writeBlock(sheet, 0, pageNum, pageKindName, topSlots);
            int want = Math.min(pageNum - 1, wb.getNumberOfSheets() - 1);
            int cur = wb.getSheetIndex(sheet);
            if (cur >= 0 && want >= 0 && cur != want) {
                wb.setSheetOrder(sheetName, want);
            }
            try (OutputStream out = Files.newOutputStream(xlsxPath)) {
                wb.write(out);
            }
        } finally {
            wb.close();
        }
    }

    private static int writeBlock(
            Sheet sheet,
            int startRow,
            int pageNum,
            String pageKindName,
            List<TreeItem<SlotValue>> topSlots) {
        int r = startRow;
        Row title = sheet.createRow(r++);
        setCell(title, 0, "第" + pageNum + "页");
        if (pageKindName != null && !pageKindName.isBlank()) {
            setCell(title, 1, pageKindName);
        }
        r++; // blank

        List<TreeItem<SlotValue>> plain = new ArrayList<>();
        List<TreeItem<SlotValue>> tables = new ArrayList<>();
        for (TreeItem<SlotValue> ti : topSlots) {
            if (ti == null || ti.getValue() == null) continue;
            if (isTable(ti)) {
                tables.add(ti);
            } else {
                plain.add(ti);
            }
        }

        if (!plain.isEmpty()) {
            Row keys = sheet.createRow(r++);
            Row vals = sheet.createRow(r++);
            for (int i = 0; i < plain.size(); i++) {
                SlotValue sv = plain.get(i).getValue();
                String key = sv.getSlotLabel();
                if (key == null || key.isBlank()) {
                    key = sv.getSlotCode();
                }
                setCell(keys, i, key);
                setCell(vals, i, sv.getValue());
            }
            r++; // blank
        }

        for (TreeItem<SlotValue> ti : tables) {
            SlotValue sv = ti.getValue();
            String tableName = sv.getSlotLabel();
            if (tableName != null && tableName.startsWith("*")) {
                int colon = tableName.indexOf(':');
                tableName = colon > 1 ? tableName.substring(1, colon).trim() : tableName.substring(1).trim();
            }
            Row nameRow = sheet.createRow(r++);
            setCell(nameRow, 0, tableName == null || tableName.isBlank() ? "表格" : tableName);

            List<List<String>> md = parseMarkdownTable(sv.getValue());
            if (md.size() >= 1) {
                for (List<String> line : md) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < line.size(); c++) {
                        setCell(row, c, line.get(c));
                    }
                }
            } else if (!ti.getChildren().isEmpty()) {
                Row header = sheet.createRow(r++);
                Row data = sheet.createRow(r++);
                int c = 0;
                for (TreeItem<SlotValue> child : ti.getChildren()) {
                    SlotValue cv = child.getValue();
                    if (cv == null) continue;
                    String h = cv.getSlotLabel();
                    if (h == null || h.isBlank()) {
                        h = cv.getSlotCode();
                    }
                    setCell(header, c, h);
                    setCell(data, c, cv.getValue());
                    c++;
                }
            } else if (sv.getValue() != null && !sv.getValue().isBlank()) {
                Row row = sheet.createRow(r++);
                setCell(row, 0, sv.getValue());
            }
            r++; // blank between tables
        }
        return r;
    }

    static boolean isTable(TreeItem<SlotValue> ti) {
        if (ti == null || ti.getValue() == null) return false;
        String label = ti.getValue().getSlotLabel();
        return !ti.getChildren().isEmpty()
                || (label != null && label.startsWith("*"))
                || looksLikeMarkdownTable(ti.getValue().getValue());
    }

    public static boolean looksLikeMarkdownTable(String text) {
        if (text == null || text.isBlank()) return false;
        int pipes = 0;
        for (String line : text.split("\n")) {
            if (line.contains("|")) pipes++;
        }
        return pipes >= 2;
    }

    public static List<List<String>> parseMarkdownTable(String md) {
        List<List<String>> out = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return out;
        }
        for (String raw : md.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (MD_SEP.matcher(line).matches()) continue;
            if (!line.contains("|")) continue;
            if (line.startsWith("|")) line = line.substring(1);
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
            String[] parts = line.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (String p : parts) {
                cells.add(p.trim()
                        .replace("<br>", "\n")
                        .replace("<br/>", "\n")
                        .replace("<br />", "\n"));
            }
            if (!cells.isEmpty()) {
                out.add(cells);
            }
        }
        return out;
    }

    private static void setCell(Row row, int c, String text) {
        Cell cell = row.getCell(c);
        if (cell == null) {
            cell = row.createCell(c);
        }
        String plain = LatexToPlainText.convert(text);
        if (plain == null || plain.isEmpty()) {
            cell.setCellValue("");
            return;
        }
        cell.setCellValue(plain);
        if (plain.indexOf('\n') >= 0) {
            var wb = cell.getSheet().getWorkbook();
            org.apache.poi.ss.usermodel.CellStyle style = wb.createCellStyle();
            style.setWrapText(true);
            cell.setCellStyle(style);
            float minHeight = 15f * plain.split("\\R", -1).length;
            if (row.getHeightInPoints() < minHeight) {
                row.setHeightInPoints(minHeight);
            }
        }
    }
}
