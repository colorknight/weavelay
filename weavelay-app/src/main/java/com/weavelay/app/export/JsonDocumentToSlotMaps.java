package com.weavelay.app.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.weavelay.core.formula.LatexToPlainText;
import com.weavelay.app.export.TemplateExcelFiller.SlotMaps;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@code OutputJsonEngine} 累计文档转成 Excel 填充用的 {@link SlotMaps}。
 * <ul>
 *   <li>根文本 → 标量</li>
 *   <li>根对象数组 → 表（一层纯标量子数组展成 {@code 设备.名称} 虚线列）</li>
 *   <li>表名：集合名；若不带「表」再注册 {@code 名+表}；{@code 原料} 另注册为 {@code 材料表}</li>
 * </ul>
 */
public final class JsonDocumentToSlotMaps {

    private static final Set<String> META_KEYS = Set.of("version", "link", "group", "route");

    private JsonDocumentToSlotMaps() {}

    public static SlotMaps fromDocument(JsonNode document) {
        SlotMaps maps = new SlotMaps();
        if (document == null || !document.isObject()) {
            return maps;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = document.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            JsonNode val = e.getValue();
            if (key == null || key.isBlank() || val == null || val.isNull()) {
                continue;
            }
            if (META_KEYS.contains(key)) {
                continue;
            }
            if (val.isTextual() || val.isNumber() || val.isBoolean()) {
                putScalar(maps, key, val.asText(""));
                continue;
            }
            if (val.isArray()) {
                List<List<String>> table = arrayToTable(val);
                if (table != null && !table.isEmpty()) {
                    registerTable(maps, key, table);
                }
            }
        }
        return maps;
    }

    private static void putScalar(SlotMaps maps, String key, String value) {
        String plain = LatexToPlainText.convert(value == null ? "" : value);
        SlotMaps.putNormalized(maps.scalars, key, plain);
    }

    private static void registerTable(SlotMaps maps, String collectionName, List<List<String>> table) {
        SlotMaps.putNormalized(maps.tables, collectionName, table);
        if (!collectionName.endsWith("表")) {
            SlotMaps.putNormalized(maps.tables, collectionName + "表", table);
        }
        if ("原料".equals(collectionName)) {
            SlotMaps.putNormalized(maps.tables, "材料表", table);
        }
    }

    /**
     * 对象数组 → [header, row...]。嵌套「纯标量对象数组」展成虚线列，多条用中文分号拼接。
     */
    static List<List<String>> arrayToTable(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<ObjectNodeRow> rows = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n != null && n.isObject()) {
                rows.add(flattenObject(n));
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> headers = new LinkedHashSet<>();
        for (ObjectNodeRow row : rows) {
            headers.addAll(row.columns.keySet());
        }
        if (headers.isEmpty()) {
            return List.of();
        }
        List<String> header = new ArrayList<>(headers);
        List<List<String>> out = new ArrayList<>();
        out.add(header);
        for (ObjectNodeRow row : rows) {
            List<String> line = new ArrayList<>(header.size());
            for (String h : header) {
                String v = row.columns.getOrDefault(h, "");
                line.add(LatexToPlainText.convert(v == null ? "" : v));
            }
            out.add(line);
        }
        return out;
    }

    private static ObjectNodeRow flattenObject(JsonNode obj) {
        ObjectNodeRow row = new ObjectNodeRow();
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode v = e.getValue();
            if (name == null || v == null || v.isNull()) {
                continue;
            }
            if (v.isTextual() || v.isNumber() || v.isBoolean()) {
                row.columns.put(name, v.asText(""));
                continue;
            }
            if (v.isArray() && isFlatObjectArray(v)) {
                Map<String, List<String>> bySub = new LinkedHashMap<>();
                for (JsonNode item : v) {
                    Iterator<Map.Entry<String, JsonNode>> sit = item.fields();
                    while (sit.hasNext()) {
                        Map.Entry<String, JsonNode> se = sit.next();
                        String sub = se.getKey();
                        JsonNode sv = se.getValue();
                        if (sub == null || sv == null || !(sv.isTextual() || sv.isNumber() || sv.isBoolean())) {
                            continue;
                        }
                        String text = sv.asText("");
                        if (text.isBlank()) {
                            continue;
                        }
                        bySub.computeIfAbsent(sub, k -> new ArrayList<>()).add(text);
                    }
                }
                for (Map.Entry<String, List<String>> se : bySub.entrySet()) {
                    row.columns.put(name + "." + se.getKey(), String.join("；", se.getValue()));
                }
            }
            // 含更深层嵌套的数组（如工步）跳过，留给后续专用映射
        }
        return row;
    }

    private static boolean isFlatObjectArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return false;
        }
        for (JsonNode n : arr) {
            if (n == null || !n.isObject()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                JsonNode v = it.next().getValue();
                if (v != null && (v.isArray() || v.isObject())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class ObjectNodeRow {
        final Map<String, String> columns = new LinkedHashMap<>();
    }
}
