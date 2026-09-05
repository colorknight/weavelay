package com.weavelay.core.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 输出定义 JSON Schema（编辑器补全 / 校验）。
 */
public final class FamilyOutputJsonSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FamilyOutputJsonSchema() {
    }

    public static String build(List<String> pageKindNames) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "http://json-schema.org/draft-07/schema#");
        root.put("title", "文件类型输出定义");
        root.put("type", "object");

        ObjectNode props = root.putObject("properties");
        props.putObject("version")
                .put("type", "integer")
                .put("description", "结构版本")
                .put("default", 2);
        props.putObject("root")
                .put("type", "string")
                .put("description", "根实体名，如零件")
                .put("default", "零件");

        ObjectNode product = props.putObject("product");
        product.put("type", "array");
        product.put("description", "根上标量字段名");
        product.putObject("items").put("type", "string");

        ObjectNode collections = props.putObject("collections");
        collections.put("type", "object");
        collections.put("description", "集合名 → 定义（从属于根）");
        ObjectNode colItem = collections.putObject("additionalProperties");
        colItem.put("type", "object");
        ObjectNode colProps = colItem.putObject("properties");
        colProps.putObject("sourceTable").put("type", "string").put("description", "OCR/识别表名");
        ObjectNode columns = colProps.putObject("columns");
        columns.put("type", "array");
        columns.putObject("items").put("type", "string");
        colProps.putObject("matchKey").put("type", "string").put("description", "匹配列");
        colProps.putObject("entryId").put("type", "string").put("description", "条目主键");
        ObjectNode cards = colProps.putObject("cards");
        cards.put("type", "object");
        cards.put("description", "条目下从属卡片");
        ObjectNode cardItem = cards.putObject("additionalProperties");
        cardItem.put("type", "object");
        ObjectNode cardFields = cardItem.putObject("properties").putObject("fields");
        cardFields.put("type", "array");
        cardFields.putObject("items").put("type", "string");

        // 兼容旧 tables
        ObjectNode tables = props.putObject("tables");
        tables.put("type", "object");
        tables.put("description", "兼容旧版表定义（可由 collections 同步）");
        ObjectNode tableItem = tables.putObject("additionalProperties");
        tableItem.put("type", "object");
        ObjectNode tableProps = tableItem.putObject("properties");
        ObjectNode tcols = tableProps.putObject("columns");
        tcols.put("type", "array");
        tcols.putObject("items").put("type", "string");
        tableProps.putObject("matchKey").put("type", "string");
        tableProps.putObject("entryId").put("type", "string");

        ObjectNode pageWrites = props.putObject("pageWrites");
        pageWrites.put("type", "array");
        pageWrites.put("description", "各页型如何写入实例 JSON");
        ObjectNode writeItem = pageWrites.putObject("items");
        writeItem.put("type", "object");
        ObjectNode writeProps = writeItem.putObject("properties");

        ObjectNode pageKind = writeProps.putObject("pageKind");
        pageKind.put("type", "string");
        pageKind.put("description", "页面类型");
        if (pageKindNames != null && !pageKindNames.isEmpty()) {
            ArrayNode enums = pageKind.putArray("enum");
            for (String name : pageKindNames) {
                if (name != null && !name.isBlank()) {
                    enums.add(name.trim());
                }
            }
        }

        ObjectNode mode = writeProps.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "写入方式");
        ArrayNode modeEnum = mode.putArray("enum");
        modeEnum.add("setScalars");
        modeEnum.add("mergeTable");
        modeEnum.add("patchEntry");

        writeProps.putObject("target")
                .put("type", "string")
                .put("description", "路径：零件 / 零件.工序 / 零件.工序.机加工序");
        writeProps.putObject("matchKey").put("type", "string");
        writeProps.putObject("card").put("type", "string");
        writeProps.putObject("collection").put("type", "string");

        ArrayNode writeRequired = writeItem.putArray("required");
        writeRequired.add("pageKind");
        writeRequired.add("mode");

        ArrayNode required = root.putArray("required");
        required.add("version");
        required.add("root");
        required.add("product");
        required.add("collections");
        required.add("pageWrites");

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成 JSON Schema", ex);
        }
    }
}
