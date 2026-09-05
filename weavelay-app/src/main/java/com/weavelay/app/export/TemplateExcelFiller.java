package com.weavelay.app.export;

import com.weavelay.core.formula.LatexToPlainText;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.store.TemplateRule;
import javafx.scene.control.TreeItem;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.common.usermodel.HyperlinkType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按模板里出现的 {@code {{占位符}}} 填充。
 * <p><strong>不做 Sheet 名绑定</strong>：主表叫什么都行，只扫占位符。
 * 模板里预先放齐各类型 Sheet；同类工序合并写入同一 Sheet，不再按条目克隆。
 * <ul>
 *   <li>{@code {{产品代号}}} / {@code {{材料表,2,零件重量kg}}} — 见格替换</li>
 *   <li>整格 {@code {{工序目录表}}} — 展开数据行</li>
 *   <li>整格 {@code {{+工序目录表,工序号,工序名称}}} — 展开目录；
 *       中间列用输出定义 {@code route[]} 正则匹配 {@code route.target}（旧名 {@code template}）Sheet；
 *       末列表示「命名列也加超链接」（不再用来起新 Sheet 名）；
 *       对不上规则或找不到 target Sheet 则不加链接；匹配行跳转到对应类型 Sheet</li>
 * </ul>
 */
public final class TemplateExcelFiller {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final DataFormatter FORMATTER = new DataFormatter();

    private TemplateExcelFiller() {}

    static {
        relaxZipBombLimit();
    }

    /**
     * 工业 Excel 模板的 {@code styles.xml} 常被压得很狠，POI 默认解压比 0.01 会误报 zip bomb。
     * 在打开任何 xlsx 前调用。
     */
    public static void relaxZipBombLimit() {
        // 报错例：ratio 0.009854 < MIN_INFLATE_RATIO 0.010000（Entry: xl/styles.xml）
        ZipSecureFile.setMinInflateRatio(0.001d);
    }

    /**
     * @param matchColumn 用哪一列跑 route 正则（如工序号）
     * @param nameColumn  也加超链接的列；可用 {@code +} 拼接时不加命名列链接；空则仅匹配列
     */
    record TableAnchor(
            int row,
            int col,
            String tableKey,
            boolean spawnSheets,
            String matchColumn,
            String nameColumn) {}

    /**
     * 输出文件尚不存在时，从模板复制一份（已存在则保留）。
     */
    public static void ensureOutputFromTemplate(Path templatePath, Path outputPath) throws IOException {
        if (templatePath == null || !Files.isRegularFile(templatePath)) {
            throw new IOException("输出模板不存在: " + templatePath);
        }
        if (Files.isRegularFile(outputPath)) {
            return;
        }
        Files.createDirectories(outputPath.getParent());
        Files.copy(templatePath, outputPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void fill(Path xlsxPath, List<TreeItem<SlotValue>> topSlots) throws IOException {
        fill(xlsxPath, null, topSlots, List.of());
    }

    public static void fill(
            Path xlsxPath,
            List<TreeItem<SlotValue>> topSlots,
            List<TemplateRule> templateRules) throws IOException {
        fill(xlsxPath, null, topSlots, templateRules);
    }

    /**
     * @param templatePath 原始输出模板；输出簿缺少 {@code route.target} Sheet 时按需从模板拷入
     */
    public static void fill(
            Path xlsxPath,
            Path templatePath,
            List<TreeItem<SlotValue>> topSlots,
            List<TemplateRule> templateRules) throws IOException {
        fill(xlsxPath, templatePath, SlotMaps.fromSlots(topSlots), templateRules);
    }

    /** 直接用已构建的 {@link SlotMaps}（如从累计 JSON 转换）填充。 */
    public static void fill(
            Path xlsxPath,
            Path templatePath,
            SlotMaps maps,
            List<TemplateRule> templateRules) throws IOException {
        if (maps == null) {
            maps = new SlotMaps();
        }
        List<TemplateRule> rules = templateRules == null ? List.of() : templateRules;
        try (InputStream in = Files.newInputStream(xlsxPath);
             Workbook wb = WorkbookFactory.create(in)) {
            List<String> sheetNames = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                sheetNames.add(wb.getSheetName(i));
            }
            for (String name : sheetNames) {
                Sheet sheet = wb.getSheet(name);
                if (sheet != null) {
                    fillSheet(sheet, maps, rules, templatePath);
                }
            }
            // 目录链接时可能从模板补入 target Sheet，再填一轮
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                String name = wb.getSheetName(i);
                if (sheetNames.contains(name)) {
                    continue;
                }
                Sheet sheet = wb.getSheet(name);
                if (sheet != null) {
                    fillSheet(sheet, maps, rules, templatePath);
                }
            }
            try (OutputStream out = Files.newOutputStream(xlsxPath)) {
                wb.write(out);
            }
        }
    }

    /** 按需从原始模板拷入缺失的 target Sheet（同类合并写入，只拷一次）。 */
    static void ensureTargetSheet(Workbook outWb, Path templatePath, String sheetName)
            throws IOException {
        if (outWb == null || sheetName == null || sheetName.isBlank()
                || outWb.getSheet(sheetName) != null
                || templatePath == null || !Files.isRegularFile(templatePath)) {
            return;
        }
        try (InputStream in = Files.newInputStream(templatePath);
             Workbook tplWb = WorkbookFactory.create(in)) {
            Sheet src = tplWb.getSheet(sheetName);
            if (src == null) {
                return;
            }
            Sheet dst = outWb.createSheet(sheetName);
            copySheetContents(src, dst);
        }
    }

    /** 跨工作簿拷贝单元格值/样式与合并区。 */
    static void copySheetContents(Sheet src, Sheet dst) {
        if (src == null || dst == null) {
            return;
        }
        Workbook dstWb = dst.getWorkbook();
        for (int r = 0; r <= src.getLastRowNum(); r++) {
            Row srcRow = src.getRow(r);
            if (srcRow == null) {
                continue;
            }
            Row dstRow = dst.createRow(r);
            dstRow.setHeight(srcRow.getHeight());
            short last = srcRow.getLastCellNum();
            for (int c = 0; c < last; c++) {
                Cell sc = srcRow.getCell(c);
                if (sc == null) {
                    continue;
                }
                Cell dc = dstRow.createCell(c);
                copyCell(sc, dc, dstWb);
            }
        }
        for (int m = 0; m < src.getNumMergedRegions(); m++) {
            CellRangeAddress region = src.getMergedRegion(m);
            if (region != null) {
                dst.addMergedRegion(region.copy());
            }
        }
    }

    private static void copyCell(Cell src, Cell dst, Workbook dstWb) {
        CellType type = src.getCellType();
        if (type == CellType.FORMULA) {
            type = src.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING -> dst.setCellValue(src.getStringCellValue());
            case NUMERIC -> dst.setCellValue(src.getNumericCellValue());
            case BOOLEAN -> dst.setCellValue(src.getBooleanCellValue());
            case BLANK -> dst.setBlank();
            default -> dst.setCellValue(FORMATTER.formatCellValue(src));
        }
        try {
            CellStyle style = dstWb.createCellStyle();
            style.cloneStyleFrom(src.getCellStyle());
            dst.setCellStyle(style);
        } catch (Exception ignored) {
            // 跨簿样式偶发失败时仍保留值
        }
    }

    /**
     * @return 本页是否为目录行解析过 target Sheet 超链接
     */
    static boolean fillSheet(
            Sheet sheet, SlotMaps maps, List<TemplateRule> rules, Path templatePath)
            throws IOException {
        List<TableAnchor> tableAnchors = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            short lastCell = row.getLastCellNum();
            if (lastCell < 0) {
                continue;
            }
            for (int c = 0; c < lastCell; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    continue;
                }
                String text = cellString(cell);
                if (text.isEmpty() || text.indexOf('{') < 0) {
                    continue;
                }
                String trimmed = text.trim();
                TableAnchor anchor = parseTableAnchor(trimmed, r, c, maps);
                if (anchor != null) {
                    tableAnchors.add(anchor);
                    continue;
                }
                String replaced = replacePlaceholders(text, maps);
                if (!replaced.equals(text)) {
                    writePlain(cell, replaced);
                }
            }
        }
        Workbook wb = sheet.getWorkbook();
        boolean spawned = false;
        for (TableAnchor anchor : tableAnchors) {
            Map<Integer, String> rowSheets = Map.of();
            if (anchor.spawnSheets()) {
                rowSheets = spawnEntrySheets(wb, anchor, maps, rules, templatePath);
                spawned |= !rowSheets.isEmpty();
            }
            expandTable(sheet, anchor, maps, wb, rules, rowSheets);
        }
        return spawned;
    }

    /**
     * {@code {{表}}} / {@code {{+表}}} / {@code {{+表,匹配列}}} /
     * {@code {{+表,匹配列,命名列}} / {@code {{+表,匹配列,列A+列B}}}。
     */
    static TableAnchor parseTableAnchor(String trimmed, int row, int col, SlotMaps maps) {
        if (!trimmed.startsWith("{{") || !trimmed.endsWith("}}") || trimmed.indexOf("{{", 2) >= 0) {
            return null;
        }
        String inner = trimmed.substring(2, trimmed.length() - 2).trim();
        if (inner.isEmpty()) {
            return null;
        }
        boolean spawn = false;
        if (inner.charAt(0) == '+') {
            spawn = true;
            inner = inner.substring(1).trim();
            if (inner.isEmpty()) {
                return null;
            }
        }
        String tableKey;
        String matchColumn = null;
        String nameColumn = null;
        if (spawn && inner.indexOf(',') >= 0) {
            String[] parts = inner.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                tableKey = parts[0];
                matchColumn = parts[1];
                nameColumn = parts[1];
            } else if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty()
                    && !parts[2].isEmpty()) {
                tableKey = parts[0];
                matchColumn = parts[1];
                nameColumn = parts[2];
            } else {
                return null;
            }
        } else if (spawn && inner.indexOf('.') >= 0) {
            // 兼容点号：+表.匹配列 或 +表.匹配列.命名列（命名列不含点）
            String[] parts = inner.split("\\.", -1);
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()
                    && !parts[1].matches("[1-9]\\d*")) {
                tableKey = parts[0];
                matchColumn = parts[1];
                nameColumn = parts[1];
            } else if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty()
                    && !parts[2].isEmpty() && !parts[1].matches("[1-9]\\d*")) {
                tableKey = parts[0];
                matchColumn = parts[1];
                nameColumn = parts[2];
            } else {
                return null;
            }
        } else if (!spawn && (inner.indexOf(',') >= 0 || inner.indexOf('.') >= 0)) {
            return null;
        } else {
            tableKey = inner;
        }
        if (lookupTable(maps.tables, tableKey) == null) {
            return null;
        }
        return new TableAnchor(row, col, tableKey, spawn, matchColumn, nameColumn);
    }

    private static String replacePlaceholders(String text, SlotMaps maps) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            String key = m.group(1).trim();
            // 无值也清空占位符，避免 Excel 里留下 {{材料表.零件重量kg}} 这类原文
            String val = resolveValue(key, maps);
            if (val == null) {
                val = "";
            }
            any = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        if (!any) {
            return text;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析占位符：普通字段 → {@code 表.列} / {@code 表,列} → {@code 表.行.列} / {@code 表,行,列}。
     * @return null 表示未识别（写出时按空串处理）
     */
    static String resolveValue(String key, SlotMaps maps) {
        String direct = lookupScalar(maps.scalars, key);
        if (direct != null) {
            return direct;
        }
        String[] parts = splitTableRef(key);
        if (parts == null) {
            return null;
        }
        String table = parts[0];
        int dataRow; // 1-based；0 = 首个非空
        String column;
        if (parts.length == 2) {
            dataRow = 0;
            column = parts[1];
        } else {
            dataRow = Integer.parseInt(parts[1]);
            column = parts[2];
        }
        // 子 Sheet 克隆上下文：{{目录表.列}} 取当前目录行，不扫全表
        if (maps.spawnTableKey != null && normalizeKey(table).equals(normalizeKey(maps.spawnTableKey))
                && maps.spawnHeader != null && maps.spawnRowLine != null) {
            int colIdx = findColumnIndex(maps.spawnHeader, column);
            if (colIdx >= 0) {
                return colIdx < maps.spawnRowLine.size()
                        ? nullToEmpty(maps.spawnRowLine.get(colIdx)) : "";
            }
            return null;
        }
        List<List<String>> md = lookupTable(maps.tables, table);
        if (md == null || md.isEmpty()) {
            return null;
        }
        int colIdx = findColumnIndex(md.get(0), column);
        if (colIdx < 0) {
            return null;
        }
        if (dataRow > 0) {
            int mdIdx = dataRow; // 表头在 0，数据行 1..n
            if (mdIdx >= md.size()) {
                return "";
            }
            List<String> line = md.get(mdIdx);
            return colIdx < line.size() ? nullToEmpty(line.get(colIdx)) : "";
        }
        for (int i = 1; i < md.size(); i++) {
            List<String> line = md.get(i);
            if (colIdx < line.size()) {
                String v = line.get(colIdx);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return "";
    }

    /**
     * {@code 表.列} / {@code 表,列} → [表,列]；
     * {@code 表.2.列} / {@code 表,2,列} → [表,行,列]。
     * 优先按逗号拆（列名里可含点）；否则按点拆，且三段时中间必须是正整数行号。
     */
    static String[] splitTableRef(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String s = key.trim();
        if (s.indexOf(',') >= 0) {
            String[] parts = s.split(",", -1);
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                return parts;
            }
            if (parts.length == 3 && !parts[0].isEmpty() && parts[1].matches("[1-9]\\d*")
                    && !parts[2].isEmpty()) {
                return parts;
            }
            return null;
        }
        if (s.indexOf('.') < 0) {
            return null;
        }
        String[] parts = s.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return parts;
        }
        if (parts.length == 3 && !parts[0].isEmpty() && parts[1].matches("[1-9]\\d*")
                && !parts[2].isEmpty()) {
            return parts;
        }
        // 列名含点：表.xxx.yyy.zzz → 仅当第二段是行号时用 表.行.其余
        if (parts.length > 3 && parts[1].matches("[1-9]\\d*")) {
            String col = String.join(".", java.util.Arrays.copyOfRange(parts, 2, parts.length));
            if (!parts[0].isEmpty() && !col.isEmpty()) {
                return new String[]{parts[0], parts[1], col};
            }
        }
        return null;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static int findColumnIndex(List<String> header, String column) {
        if (header == null || column == null) {
            return -1;
        }
        String want = normalizeKey(column);
        for (int i = 0; i < header.size(); i++) {
            if (normalizeKey(header.get(i)).equals(want)) {
                return i;
            }
        }
        // Excel 表头与 JSON 列名常见别名
        for (String alt : columnAliases(want)) {
            for (int i = 0; i < header.size(); i++) {
                if (normalizeKey(header.get(i)).equals(alt)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Excel 列名 → JSON 列名候选（normalize 后）。 */
    private static List<String> columnAliases(String normalizedExcelHeader) {
        if (normalizedExcelHeader == null || normalizedExcelHeader.isEmpty()) {
            return List.of();
        }
        return switch (normalizedExcelHeader) {
            case "技术要求" -> List.of("注");
            case "注" -> List.of("技术要求");
            case "设备名称" -> List.of("设备.名称");
            case "设备.名称" -> List.of("设备名称");
            case "设备型号" -> List.of("设备.型号");
            case "设备.型号" -> List.of("设备型号");
            default -> List.of();
        };
    }

    private static void expandTable(
            Sheet sheet, TableAnchor anchor, SlotMaps maps, Workbook wb, List<TemplateRule> rules) {
        expandTable(sheet, anchor, maps, wb, rules, Map.of());
    }

    /**
     * @param rowSheets 数据行下标（0 起，不含表头）→ 已克隆的子 Sheet 名；非空则给匹配列/命名列加跳转
     */
    private static void expandTable(
            Sheet sheet,
            TableAnchor anchor,
            SlotMaps maps,
            Workbook wb,
            List<TemplateRule> rules,
            Map<Integer, String> rowSheets) {
        int startRow = anchor.row();
        int startCol = anchor.col();
        Cell anchorCell = sheet.getRow(startRow).getCell(startCol);
        List<List<String>> md = lookupTable(maps.tables, anchor.tableKey());
        if (md == null || md.isEmpty()) {
            return;
        }
        List<List<String>> body = md.size() >= 2 ? md.subList(1, md.size()) : List.of();
        if (body.isEmpty()) {
            writePlain(anchorCell, "");
            return;
        }

        List<String> mdHeader = md.get(0);
        int[] colMap = mapColumns(sheet, startRow, startCol, mdHeader);
        CellStyle[] styles = captureStyles(sheet.getRow(startRow), startCol, colMap.length);

        int matchMdCol = -1;
        int nameMdCol = -1;
        if (anchor.spawnSheets()) {
            String matchColName = anchor.matchColumn();
            if (matchColName != null && !matchColName.isBlank()) {
                matchMdCol = findColumnIndex(mdHeader, matchColName);
            }
            String nameColName = anchor.nameColumn();
            if (nameColName != null && !nameColName.isBlank() && nameColName.indexOf('+') < 0) {
                nameMdCol = findColumnIndex(mdHeader, nameColName);
            }
        }
        Map<Integer, String> links = rowSheets == null ? Map.of() : rowSheets;

        if (body.size() > 1) {
            int shiftFrom = startRow + 1;
            int last = sheet.getLastRowNum();
            if (shiftFrom <= last) {
                sheet.shiftRows(shiftFrom, last, body.size() - 1, true, false);
            }
        }

        for (int i = 0; i < body.size(); i++) {
            int r = startRow + i;
            Row row = sheet.getRow(r);
            if (row == null) {
                row = sheet.createRow(r);
            }
            List<String> line = body.get(i);
            String sheetName = links.get(i);
            for (int j = 0; j < colMap.length; j++) {
                int excelCol = startCol + j;
                int mdCol = colMap[j];
                String val = mdCol >= 0 && mdCol < line.size() ? line.get(mdCol) : "";
                Cell cell = row.getCell(excelCol);
                if (cell == null) {
                    cell = row.createCell(excelCol);
                }
                if (styles[j] != null) {
                    cell.setCellStyle(styles[j]);
                }
                boolean linkCol = sheetName != null && !sheetName.isEmpty()
                        && mdCol >= 0
                        && (mdCol == matchMdCol || mdCol == nameMdCol);
                if (linkCol && wb != null) {
                    setInternalSheetLink(cell, wb, sheetName, val);
                    continue;
                }
                writePlain(cell, val);
            }
        }
    }

    /**
     * 按 route.target 解析已有 Sheet，供目录超链接（不克隆）。
     * @return 数据行下标（0 起）→ target Sheet 名
     */
    private static Map<Integer, String> spawnEntrySheets(
            Workbook wb,
            TableAnchor anchor,
            SlotMaps maps,
            List<TemplateRule> rules,
            Path templatePath) throws IOException {
        Map<Integer, String> rowSheets = new LinkedHashMap<>();
        if (rules == null || rules.isEmpty()) {
            return rowSheets;
        }
        String matchColName = anchor.matchColumn();
        if (matchColName == null || matchColName.isBlank()) {
            return rowSheets;
        }
        List<List<String>> md = lookupTable(maps.tables, anchor.tableKey());
        if (md == null || md.size() < 2) {
            return rowSheets;
        }
        List<String> header = md.get(0);
        int matchIdx = findColumnIndex(header, matchColName);
        if (matchIdx < 0) {
            return rowSheets;
        }

        for (int i = 1; i < md.size(); i++) {
            List<String> line = md.get(i);
            String matchVal = matchIdx < line.size() ? nullToEmpty(line.get(matchIdx)) : "";
            TemplateRule rule = matchTargetRule(rules, matchVal);
            if (rule == null) {
                continue;
            }
            String targetName = resolveTargetSheetName(rule.getName(), wb, templatePath);
            if (targetName.isEmpty()) {
                continue;
            }
            if (wb.getSheet(targetName) == null) {
                ensureTargetSheet(wb, templatePath, targetName);
            }
            if (wb.getSheet(targetName) == null) {
                continue;
            }
            rowSheets.put(i - 1, targetName);
        }
        return rowSheets;
    }

    /**
     * 子 Sheet：列头格与目录表列名一致时，把该行对应值写入正下方单元格（空或占位符才写）。
     */
    static void fillCatalogColumnsBelowHeaders(Sheet sheet, List<String> header, List<String> line) {
        if (sheet == null || header == null || header.isEmpty() || line == null) {
            return;
        }
        int lastRow = sheet.getLastRowNum();
        for (int r = 0; r < lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Row below = sheet.getRow(r + 1);
            if (below == null) {
                below = sheet.createRow(r + 1);
            }
            short lastCell = row.getLastCellNum();
            if (lastCell < 0) {
                continue;
            }
            for (int c = 0; c < lastCell; c++) {
                String h = cellString(row.getCell(c)).trim();
                if (h.isEmpty()) {
                    continue;
                }
                int colIdx = findColumnIndex(header, h);
                if (colIdx < 0) {
                    continue;
                }
                String val = colIdx < line.size() ? nullToEmpty(line.get(colIdx)) : "";
                Cell target = below.getCell(c);
                if (target == null) {
                    target = below.createCell(c);
                }
                String cur = cellString(target).trim();
                if (cur.isEmpty() || isPlaceholderOnly(cur)) {
                    writePlain(target, val);
                }
            }
        }
    }

    private static boolean isPlaceholderOnly(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("{{") && t.endsWith("}}") && t.indexOf("{{", 2) < 0;
    }

    /**
     * 解析 Sheet 命名：单列名，或 {@code 列A+列B} 按行拼接各列单元格（无分隔符）。
     * 列名/拼接结果为空时回退 {@code fallback}（通常为匹配列值）。
     */
    static String resolveSheetName(
            List<String> header, List<String> line, String nameSpec, String fallback) {
        String fb = fallback == null ? "" : fallback.trim();
        if (nameSpec == null || nameSpec.isBlank()) {
            return fb;
        }
        String spec = nameSpec.trim();
        if (spec.indexOf('+') < 0) {
            int idx = findColumnIndex(header, spec);
            if (idx >= 0 && idx < line.size()) {
                String v = nullToEmpty(line.get(idx)).trim();
                return v.isEmpty() ? fb : v;
            }
            return fb;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : spec.split("\\+")) {
            String col = part.trim();
            if (col.isEmpty()) {
                continue;
            }
            int idx = findColumnIndex(header, col);
            if (idx >= 0 && idx < line.size()) {
                String v = nullToEmpty(line.get(idx)).trim();
                if (!v.isEmpty()) {
                    sb.append(v);
                }
            }
        }
        String joined = sb.toString();
        return joined.isEmpty() ? fb : joined;
    }

    /** 与目录超链接共用：本批已占名 + 工作簿已有 Sheet。 */
    static String allocateSheetName(Workbook wb, java.util.Set<String> batchUsed, String rawName) {
        String base = sanitizeSheetName(rawName);
        if (base.isEmpty()) {
            return "";
        }
        if (wb != null && wb.getSheet(base) == null
                && (batchUsed == null || !batchUsed.contains(base))) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String suffix = "(" + n + ")";
            String candidate = base;
            if (candidate.length() + suffix.length() > 31) {
                candidate = candidate.substring(0, Math.max(1, 31 - suffix.length()));
            }
            candidate = candidate + suffix;
            if ((wb == null || wb.getSheet(candidate) == null)
                    && (batchUsed == null || !batchUsed.contains(candidate))) {
                return candidate;
            }
        }
        return "";
    }

    private static void setInternalSheetLink(Cell cell, Workbook wb, String sheetName, String display) {
        if (cell == null || wb == null || sheetName == null || sheetName.isEmpty()) {
            return;
        }
        String text = LatexToPlainText.convert(display == null ? "" : display);
        CreationHelper helper = wb.getCreationHelper();
        Hyperlink link = helper.createHyperlink(HyperlinkType.DOCUMENT);
        String quoted = sheetName.replace("'", "''");
        link.setAddress("'" + quoted + "'!A1");
        cell.setHyperlink(link);
        cell.setCellValue(text);
        // 保留原有边框/填充，只改成可辨认的超链接字色
        CellStyle base = cell.getCellStyle();
        CellStyle style = wb.createCellStyle();
        if (base != null) {
            style.cloneStyleFrom(base);
        }
        org.apache.poi.ss.usermodel.Font font = wb.createFont();
        if (base != null && base.getFontIndexAsInt() >= 0) {
            org.apache.poi.ss.usermodel.Font old = wb.getFontAt(base.getFontIndexAsInt());
            font.setFontName(old.getFontName());
            font.setFontHeight(old.getFontHeight());
            font.setBold(old.getBold());
        }
        font.setUnderline(org.apache.poi.ss.usermodel.Font.U_SINGLE);
        font.setColor(org.apache.poi.ss.usermodel.IndexedColors.BLUE.getIndex());
        style.setFont(font);
        cell.setCellStyle(style);
    }

    static TemplateRule matchRule(List<TemplateRule> rules, String value) {
        if (rules == null || value == null) {
            return null;
        }
        for (TemplateRule rule : rules) {
            if (rule.getRegex() == null || rule.getRegex().isBlank()) {
                continue;
            }
            try {
                if (Pattern.compile(rule.getRegex()).matcher(value).find()) {
                    return rule;
                }
            } catch (Exception ignored) {
                // 非法正则跳过
            }
        }
        return null;
    }

    static TemplateRule matchTargetRule(List<TemplateRule> rules, String value) {
        return matchRule(rules, value);
    }

    /** 规则名即 Excel {@code route.target} Sheet 全名。 */
    static String resolveTargetSheetName(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) {
            return "";
        }
        return sanitizeSheetName(ruleName.trim());
    }

    /** 在输出或原始模板中查找与规则名完全一致的 target Sheet。 */
    static String resolveTargetSheetName(String ruleName, Workbook wb, Path templatePath) {
        String name = resolveTargetSheetName(ruleName);
        if (name.isEmpty()) {
            return "";
        }
        return sheetExists(wb, templatePath, name) ? name : "";
    }

    private static boolean sheetExists(Workbook wb, Path templatePath, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (wb != null && wb.getSheet(name) != null) {
            return true;
        }
        if (templatePath == null || !Files.isRegularFile(templatePath)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(templatePath);
             Workbook tpl = WorkbookFactory.create(in)) {
            return tpl.getSheet(name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String sanitizeSheetName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[\\\\/?*\\[\\]:]", "_").trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > 31) {
            s = s.substring(0, 31);
        }
        return s;
    }

    /**
     * 用锚点上一行（若有）的表头与 MD/JSON 表头按名对齐。
     * 对不上的列写空，绝不按位置硬套（否则「技术要求」会吃到「设备.名称」）。
     */
    private static int[] mapColumns(Sheet sheet, int startRow, int startCol, List<String> mdHeader) {
        Row headerRow = startRow > 0 ? sheet.getRow(startRow - 1) : null;
        if (headerRow == null || mdHeader == null || mdHeader.isEmpty()) {
            int n = Math.max(1, mdHeader == null ? 1 : mdHeader.size());
            int[] identity = new int[n];
            for (int i = 0; i < n; i++) {
                identity[i] = i;
            }
            return identity;
        }
        List<String> excelHeaders = new ArrayList<>();
        short last = headerRow.getLastCellNum();
        for (int c = startCol; c < last; c++) {
            String h = cellString(headerRow.getCell(c)).trim();
            if (h.isEmpty() && c > startCol) {
                break;
            }
            excelHeaders.add(h);
        }
        if (excelHeaders.isEmpty()) {
            int n = mdHeader.size();
            int[] identity = new int[n];
            for (int i = 0; i < n; i++) {
                identity[i] = i;
            }
            return identity;
        }
        int[] map = new int[excelHeaders.size()];
        int namedHits = 0;
        for (int j = 0; j < excelHeaders.size(); j++) {
            map[j] = findColumnIndex(mdHeader, excelHeaders.get(j));
            if (map[j] >= 0) {
                namedHits++;
            }
        }
        // 上一行完全对不上表头（例如锚点上方不是列名）→ 退回按列序；
        // 有对上的则其余列留空，禁止位置硬套导致「技术要求」吃到设备列。
        if (namedHits == 0) {
            for (int j = 0; j < map.length; j++) {
                map[j] = j < mdHeader.size() ? j : -1;
            }
        }
        return map;
    }

    private static CellStyle[] captureStyles(Row templateRow, int startCol, int n) {
        CellStyle[] styles = new CellStyle[n];
        if (templateRow == null) {
            return styles;
        }
        for (int j = 0; j < n; j++) {
            Cell c = templateRow.getCell(startCol + j);
            if (c != null) {
                styles[j] = c.getCellStyle();
            }
        }
        return styles;
    }

    private static String lookupScalar(Map<String, String> scalars, String key) {
        if (scalars.containsKey(key)) {
            return scalars.get(key);
        }
        String nk = normalizeKey(key);
        if (scalars.containsKey(nk)) {
            return scalars.get(nk);
        }
        return null;
    }

    private static List<List<String>> lookupTable(Map<String, List<List<String>>> tables, String key) {
        if (tables.containsKey(key)) {
            return tables.get(key);
        }
        return tables.get(normalizeKey(key));
    }

    static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        String s = key.trim();
        if (s.startsWith("$")) {
            s = s.substring(1).trim();
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return FORMATTER.formatCellValue(cell);
    }

    private static void writePlain(Cell cell, String text) {
        cell.setCellValue(LatexToPlainText.convert(text == null ? "" : text));
    }

    /** 槽位 → 标量 / 表格（键为 slotCode、显示名，大小写不敏感）。 */
    static final class SlotMaps {
        final Map<String, String> scalars = new LinkedHashMap<>();
        final Map<String, List<List<String>>> tables = new LinkedHashMap<>();
        /** 子 Sheet 克隆时绑定的目录表行（仅本轮填充有效）。 */
        String spawnTableKey;
        List<String> spawnHeader;
        List<String> spawnRowLine;

        static SlotMaps fromSlots(List<TreeItem<SlotValue>> topSlots) {
            SlotMaps maps = new SlotMaps();
            if (topSlots == null) {
                return maps;
            }
            for (TreeItem<SlotValue> ti : topSlots) {
                if (ti == null || ti.getValue() == null) {
                    continue;
                }
                SlotValue sv = ti.getValue();
                if (ConfirmExcelWriter.isTable(ti)) {
                    putTable(maps, ti, sv);
                } else {
                    putScalar(maps, sv.getSlotCode(), sv.getSlotLabel(), sv.getValue());
                }
            }
            return maps;
        }

        /** 克隆子 Sheet 时用：目录表该行各列同时作为标量 + 表.列 解析上下文。 */
        SlotMaps overlayCatalogRow(String tableKey, List<String> header, List<String> line) {
            SlotMaps copy = new SlotMaps();
            copy.scalars.putAll(this.scalars);
            copy.tables.putAll(this.tables);
            if (header != null) {
                for (int i = 0; i < header.size(); i++) {
                    String col = header.get(i);
                    String val = LatexToPlainText.convert(
                            (line != null && i < line.size()) ? line.get(i) : "");
                    putKey(copy.scalars, col, val);
                }
            }
            copy.spawnTableKey = tableKey;
            copy.spawnHeader = header == null ? List.of() : List.copyOf(header);
            copy.spawnRowLine = line == null ? List.of() : List.copyOf(line);
            return copy;
        }

        /** 供 {@link JsonDocumentToSlotMaps} 等写入标量/表。 */
        static <T> void putNormalized(Map<String, T> map, String key, T value) {
            putKey(map, key, value);
        }

        private static void putScalar(SlotMaps maps, String code, String label, String value) {
            String plain = LatexToPlainText.convert(value == null ? "" : value);
            putKey(maps.scalars, code, plain);
            putKey(maps.scalars, label, plain);
        }

        private static void putTable(SlotMaps maps, TreeItem<SlotValue> ti, SlotValue sv) {
            String tableName = sv.getSlotLabel();
            if (tableName != null && tableName.startsWith("*")) {
                int colon = tableName.indexOf(':');
                tableName = colon > 1 ? tableName.substring(1, colon).trim() : tableName.substring(1).trim();
            }
            List<List<String>> md = ConfirmExcelWriter.parseMarkdownTable(sv.getValue());
            if (md.isEmpty() && !ti.getChildren().isEmpty()) {
                List<String> header = new ArrayList<>();
                List<String> data = new ArrayList<>();
                for (TreeItem<SlotValue> child : ti.getChildren()) {
                    SlotValue cv = child.getValue();
                    if (cv == null) {
                        continue;
                    }
                    String h = cv.getSlotLabel();
                    if (h == null || h.isBlank()) {
                        h = cv.getSlotCode();
                    }
                    if (h != null && h.startsWith("$")) {
                        h = h.substring(1);
                    }
                    header.add(h == null ? "" : h);
                    data.add(LatexToPlainText.convert(cv.getValue() == null ? "" : cv.getValue()));
                }
                if (!header.isEmpty()) {
                    md = List.of(header, data);
                }
            }
            if (md.isEmpty()) {
                return;
            }
            List<List<String>> converted = new ArrayList<>();
            for (List<String> line : md) {
                List<String> row = new ArrayList<>(line.size());
                for (String cell : line) {
                    row.add(LatexToPlainText.convert(cell));
                }
                converted.add(row);
            }
            putKey(maps.tables, sv.getSlotCode(), converted);
            putKey(maps.tables, tableName, converted);
        }

        private static <T> void putKey(Map<String, T> map, String key, T value) {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            String trimmed = key.trim();
            map.put(trimmed, value);
            map.put(normalizeKey(trimmed), value);
        }
    }
}
