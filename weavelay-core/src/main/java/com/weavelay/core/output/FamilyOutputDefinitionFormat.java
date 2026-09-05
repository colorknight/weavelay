package com.weavelay.core.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 文件类型输出定义 JSON 读写。
 */
public final class FamilyOutputDefinitionFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private FamilyOutputDefinitionFormat() {
    }

    public static FamilyOutputDefinition parse(String json) {
        if (json == null || json.isBlank()) {
            return new FamilyOutputDefinition();
        }
        try {
            FamilyOutputDefinition def = MAPPER.readValue(json.trim(), FamilyOutputDefinition.class);
            if (def == null) {
                return new FamilyOutputDefinition();
            }
            normalize(def);
            return def;
        } catch (Exception ex) {
            throw new IllegalArgumentException("输出定义 JSON 无法解析: " + ex.getMessage(), ex);
        }
    }

    public static String format(FamilyOutputDefinition def) {
        if (def == null) {
            return "";
        }
        normalize(def);
        def.syncTablesFromCollections();
        try {
            return MAPPER.writeValueAsString(def);
        } catch (Exception ex) {
            throw new IllegalArgumentException("输出定义无法序列化: " + ex.getMessage(), ex);
        }
    }

    public static FamilyOutputDefinition sample() {
        FamilyOutputDefinition def = new FamilyOutputDefinition();
        def.version = 2;
        def.root = "零件";
        def.product.add("产品代号");
        def.product.add("零部件名称");
        def.product.add("零部件代号");

        FamilyOutputDefinition.CollectionDef ops = new FamilyOutputDefinition.CollectionDef();
        ops.sourceTable = "工序目录表";
        ops.columns.add("工序号");
        ops.columns.add("工序名称");
        ops.columns.add("工序类型");
        ops.matchKey = "工序号";
        ops.entryId = "工序号";
        FamilyOutputDefinition.CardDef card = new FamilyOutputDefinition.CardDef();
        ops.cards.put("机加工序", card);
        def.collections.put("工序", ops);

        def.pageWrites.add(pageWrite("封面", "setScalars", "零件", "", "", ""));
        def.pageWrites.add(pageWrite("工序目录", "mergeTable", "零件.工序", "", "", "工序"));
        def.pageWrites.add(pageWrite("机加工序卡片", "patchEntry", "零件.工序.机加工序",
                "工序号", "机加工序", "工序"));
        def.syncTablesFromCollections();
        return def;
    }

    private static void normalize(FamilyOutputDefinition def) {
        if (def.root == null || def.root.isBlank()) {
            def.root = "零件";
        }
        if (def.product == null) {
            def.product = new java.util.ArrayList<>();
        }
        if (def.collections == null) {
            def.collections = new java.util.LinkedHashMap<>();
        }
        if (def.tables == null) {
            def.tables = new java.util.LinkedHashMap<>();
        }
        if (def.pageWrites == null) {
            def.pageWrites = new java.util.ArrayList<>();
        }
        def.migrateTablesToCollections();
        if (def.version < 2) {
            def.version = 2;
        }
        for (FamilyOutputDefinition.CollectionDef c : def.collections.values()) {
            if (c == null) {
                continue;
            }
            if (c.columns == null) {
                c.columns = new java.util.ArrayList<>();
            }
            if (c.cards == null) {
                c.cards = new java.util.LinkedHashMap<>();
            }
            if ((c.matchKey == null || c.matchKey.isBlank()) && !c.columns.isEmpty()) {
                c.matchKey = c.columns.get(0);
            }
            if (c.entryId == null || c.entryId.isBlank()) {
                c.entryId = c.matchKey == null ? "" : c.matchKey;
            }
        }
    }

    private static FamilyOutputDefinition.PageWriteDef pageWrite(
            String kind, String mode, String target, String matchKey, String card, String collection) {
        FamilyOutputDefinition.PageWriteDef p = new FamilyOutputDefinition.PageWriteDef();
        p.pageKind = kind;
        p.mode = mode;
        p.target = target;
        p.matchKey = matchKey;
        p.card = card;
        p.collection = collection;
        return p;
    }
}
