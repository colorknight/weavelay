package com.weavelay.core.page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 页型输出定义，每行一种写法:
 * <ul>
 *   <li>{@code 字段名=#N} — 取 merge 第 N 行作为输出值</li>
 *   <li>{@code 字段名=} 或 {@code 字段名} — 先占位，行号稍后填</li>
 *   <li>{@code key=label} — 旧版键名=标签 (兼容)</li>
 * </ul>
 */
public final class SlotDefinitionFormat {

    private static final Pattern LEGACY_CODE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern LINE_REF_VALUE = Pattern.compile("#?\\d+");

    private SlotDefinitionFormat() {
    }

    public static String format(List<SlotDefinition> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        List<SlotDefinition> sorted = new ArrayList<SlotDefinition>(slots);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
        StringBuilder sb = new StringBuilder();
        for (SlotDefinition slot : sorted) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (slot.isTable()) {
                sb.append('*').append(slot.getSlotLabel());
                String meta = slot.getFieldMeta();
                if (meta != null && !meta.isEmpty()) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode jn = om.readTree(meta);
                        if (jn.has("columns")) {
                            sb.append(':');
                            boolean first = true;
                            for (com.fasterxml.jackson.databind.JsonNode c : jn.get("columns")) {
                                if (!first) sb.append(',');
                                String colName = c.asText();
                                if (slot.isFormulaColumn(colName)) {
                                    sb.append(SlotDefinition.FORMULA_PREFIX);
                                }
                                sb.append(colName);
                                first = false;
                            }
                        }
                    } catch (Exception ignored) { }
                }
            } else if (slot.isLineRefBinding()) {
                if (slot.isFormulaField()) {
                    sb.append(SlotDefinition.FORMULA_PREFIX);
                }
                sb.append(slot.getSlotLabel());
                sb.append('=');
                if (slot.getLineRefOrNull() != null) {
                    sb.append('#').append(slot.getLineRefOrNull());
                }
            } else if (slot.getSlotCode().startsWith("pending_")) {
                if (slot.isFormulaField()) {
                    sb.append(SlotDefinition.FORMULA_PREFIX);
                }
                sb.append(slot.getSlotLabel());
            } else {
                sb.append(slot.getSlotCode()).append('=');
                if (slot.isFormulaField()) {
                    sb.append(SlotDefinition.FORMULA_PREFIX);
                }
                sb.append(slot.getSlotLabel());
            }
        }
        return sb.toString();
    }

    public static List<SlotDefinition> parse(String text) {
        List<SlotDefinition> slots = new ArrayList<SlotDefinition>();
        if (text == null || text.trim().isEmpty()) {
            return slots;
        }
        String[] lines = text.split("\\r?\\n");
        int order = 1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // 表格字段: *表名:列1,列2,列3  列名支持 $前缀 标记公式列
            if (trimmed.startsWith("*")) {
                int colon = trimmed.indexOf(':');
                String tableName = colon > 1 ? trimmed.substring(1, colon).trim() : trimmed.substring(1).trim();
                String colsStr = colon > 1 ? trimmed.substring(colon + 1).trim() : "";
                String[] rawCols = colsStr.isEmpty() ? new String[0] : colsStr.split(",");
                StringBuilder meta = new StringBuilder("{\"columns\":[");
                java.util.List<String> formulaCols = new java.util.ArrayList<>();
                for (int ci = 0; ci < rawCols.length; ci++) {
                    String colName = rawCols[ci].trim();
                    if (colName.startsWith(SlotDefinition.FORMULA_PREFIX)) {
                        colName = colName.substring(SlotDefinition.FORMULA_PREFIX.length());
                        formulaCols.add(colName);
                    }
                    if (ci > 0) meta.append(",");
                    meta.append("\"").append(colName).append("\"");
                }
                meta.append("]");
                if (!formulaCols.isEmpty()) {
                    meta.append(",\"formulaColumns\":[");
                    for (int fi = 0; fi < formulaCols.size(); fi++) {
                        if (fi > 0) meta.append(",");
                        meta.append("\"").append(formulaCols.get(fi)).append("\"");
                    }
                    meta.append("]");
                }
                meta.append("}");
                slots.add(new SlotDefinition(pendingCode(order), tableName, order++, 0, 0, 0, 0, "table", meta.toString()));
                continue;
            }
            int sep = trimmed.indexOf('=');
            if (sep < 0) {
                boolean formula = trimmed.startsWith(SlotDefinition.FORMULA_PREFIX);
                slots.add(new SlotDefinition(pendingCode(order), trimmed, order++,
                        0, 0, 0, 0, "text", formula ? "{\"formula\":true}" : ""));
                continue;
            }
            String left = trimmed.substring(0, sep).trim();
            String right = trimmed.substring(sep + 1).trim();
            if (left.isEmpty()) {
                continue;
            }
            if (right.isEmpty()) {
                boolean formula = left.startsWith(SlotDefinition.FORMULA_PREFIX);
                slots.add(new SlotDefinition(pendingCode(order), left, order++,
                        0, 0, 0, 0, "text", formula ? "{\"formula\":true}" : ""));
                continue;
            }
            if (LINE_REF_VALUE.matcher(right).matches()) {
                int lineNo = parseLineNumber(right);
                if (lineNo >= 1) {
                    boolean formula = left.startsWith(SlotDefinition.FORMULA_PREFIX);
                    slots.add(new SlotDefinition("#" + lineNo, left, order++,
                            0, 0, 0, 0, "text", formula ? "{\"formula\":true}" : ""));
                }
                continue;
            }
            if (LEGACY_CODE.matcher(left).matches()) {
                boolean formula = right.startsWith(SlotDefinition.FORMULA_PREFIX);
                slots.add(new SlotDefinition(left, right, order++,
                        0, 0, 0, 0, "text", formula ? "{\"formula\":true}" : ""));
            }
        }
        return slots;
    }

    private static int parseLineNumber(String ref) {
        String digits = ref.startsWith("#") ? ref.substring(1) : ref;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String pendingCode(int order) {
        return "pending_" + order;
    }
}
