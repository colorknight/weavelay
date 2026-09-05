package com.weavelay.app.ui;

import com.weavelay.core.page.SlotDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析表格字段标签格式: *tableName:col1,col2,...
 * 消除 WeaveLayApp 中多处重复解析逻辑。
 */
public final class TableLabelParser {

    private TableLabelParser() {}

    public record ParsedTable(String tableName, String[] columns, boolean isTable) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 slotLabel 解析表格名和列头。
     * 格式: *tableName:col1,col2,...
     */
    public static ParsedTable parse(String slotLabel) {
        if (slotLabel == null || !slotLabel.startsWith("*")) {
            return new ParsedTable(slotLabel != null ? slotLabel : "", new String[0], false);
        }
        int colon = slotLabel.indexOf(':');
        String tableName;
        String[] cols;
        if (colon > 1) {
            tableName = slotLabel.substring(1, colon).trim();
            String colsStr = slotLabel.substring(colon + 1).trim();
            // 去掉可能残留的 =value 部分
            int eq = colsStr.indexOf('=');
            if (eq >= 0) {
                colsStr = colsStr.substring(0, eq).trim();
            }
            String[] rawCols = colsStr.isEmpty() ? new String[0] : colsStr.split(",");
            List<String> finalCols = new ArrayList<>();
            for (String raw : rawCols) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) finalCols.add(trimmed);
            }
            cols = finalCols.toArray(new String[0]);
        } else {
            tableName = slotLabel.substring(1).trim();
            cols = new String[0];
        }
        tableName = tableName.replaceAll("^\\*+", "");
        return new ParsedTable(tableName, cols, true);
    }

    /**
     * 从 SlotDefinition 解析完整表格标签（含 fieldMeta JSON 中的 columns）。
     * 优先使用 slotLabel，fallback 用 fieldMeta.columns。
     */
    public static ParsedTable parseFromSlotDef(SlotDefinition sd) {
        String label = sd.getSlotLabel();
        if (label == null) label = "";

        // label 已有完整列名（*表名:col1,col2）直接返回
        if (label.startsWith("*") && label.indexOf(':') > 1) {
            return parse(label);
        }

        // 是表格字段但 label 不完整，从 fieldMeta.columns 补充
        if (sd.isTable() || label.startsWith("*")) {
            String meta = sd.getFieldMeta();
            if (meta != null && !meta.isEmpty()) {
                try {
                    JsonNode jn = MAPPER.readTree(meta);
                    if (jn.has("columns") && jn.get("columns").isArray()) {
                        String tableName = label.startsWith("*") ? label.substring(1) : label;
                        int colon = tableName.indexOf(':');
                        if (colon > 0) tableName = tableName.substring(0, colon);
                        tableName = tableName.replaceAll("^\\*+", "");
                        List<String> mc = new ArrayList<>();
                        for (JsonNode c : jn.get("columns")) {
                            mc.add(c.asText());
                        }
                        return new ParsedTable(tableName, mc.toArray(new String[0]), true);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return new ParsedTable(label, new String[0], sd.isTable() || label.startsWith("*"));
    }

    /**
     * 从 SlotDefinition 拼出完整 table label 字符串（*表名:col1,col2）。
     */
    public static String buildFullTableLabel(SlotDefinition sd) {
        ParsedTable pt = parseFromSlotDef(sd);
        if (!pt.isTable() || pt.columns().length == 0) {
            return sd.getSlotLabel() != null ? sd.getSlotLabel() : "";
        }
        return "*" + pt.tableName() + ":" + String.join(",", pt.columns());
    }
}
