package com.weavelay.core.page;

/**
 * 页型槽位定义: 字段名与 merge 行号 {@code #N} 或旧版 key/label 配对.
 */
public final class SlotDefinition {

    /** 标签前缀: 标记该字段的 OCR 结果需要公式二次识别. 解析时自动吃掉, 用户看不到. */
    public static final String FORMULA_PREFIX = "$";

    private final String slotCode;
    private final String slotLabel;
    private final int sortOrder;
    private final int regionX, regionY, regionW, regionH;
    private final String fieldType;
    private final String fieldMeta;

    public SlotDefinition(String slotCode, String slotLabel, int sortOrder) {
        this(slotCode, slotLabel, sortOrder, 0, 0, 0, 0, "text", "");
    }

    public SlotDefinition(String slotCode, String slotLabel, int sortOrder,
                          int regionX, int regionY, int regionW, int regionH) {
        this(slotCode, slotLabel, sortOrder, regionX, regionY, regionW, regionH, "text", "");
    }

    public SlotDefinition(String slotCode, String slotLabel, int sortOrder,
                          int regionX, int regionY, int regionW, int regionH,
                          String fieldType, String fieldMeta) {
        this.slotCode = slotCode == null ? "" : slotCode;
        this.slotLabel = cleanLabel(slotLabel);
        if (slotLabel != null && (slotLabel.contains("=") || slotLabel.contains("**") || slotLabel.startsWith("*"))) {
            new Exception("[SlotDefinition] WARN label: " + this.slotLabel + " code=" + this.slotCode).printStackTrace();
        }
        this.sortOrder = sortOrder;
        this.regionX = regionX;
        this.regionY = regionY;
        this.regionW = regionW;
        this.regionH = regionH;
        this.fieldType = fieldType == null ? "text" : fieldType;
        this.fieldMeta = cleanMeta(fieldMeta);
    }

    public String getSlotCode() { return slotCode; }
    public String getSlotLabel() { return slotLabel; }
    public int getSortOrder() { return sortOrder; }
    public int getRegionX() { return regionX; }
    public int getRegionY() { return regionY; }
    public int getRegionW() { return regionW; }
    public int getRegionH() { return regionH; }
    public boolean hasRegion() { return regionW > 0 && regionH > 0; }
    public String getFieldType() { return fieldType; }
    public String getFieldMeta() { return fieldMeta; }
    public boolean isTable() { return "table".equals(fieldType); }

    public boolean isLineRefBinding() {
        return slotCode.startsWith("#");
    }

    /** 该字段是否标记为需要公式二次识别. */
    public boolean isFormulaField() {
        if (fieldMeta.isEmpty()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = om.readTree(fieldMeta);
            return node.has("formula") && node.get("formula").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断给定列名是否需要公式识别 (用于表格列).
     * @param columnName 表格列名 (已去除 $ 前缀的)
     */
    public boolean isFormulaColumn(String columnName) {
        if (fieldMeta.isEmpty() || columnName == null || columnName.isEmpty()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = om.readTree(fieldMeta);
            if (node.has("formulaColumns")) {
                com.fasterxml.jackson.databind.JsonNode arr = node.get("formulaColumns");
                for (com.fasterxml.jackson.databind.JsonNode c : arr) {
                    if (columnName.equals(c.asText())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /** 吃掉标签前的 {@value #FORMULA_PREFIX} 前缀 (如果有). */
    static String cleanLabel(String label) {
        if (label == null || label.isEmpty()) {
            return label == null ? "" : label;
        }
        return label.startsWith(FORMULA_PREFIX) ? label.substring(FORMULA_PREFIX.length()) : label;
    }

    /**
     * 清洗 fieldMeta JSON: 把 columns 数组里的 $ 前缀列名吃掉, 并自动迁移到 formulaColumns.
     * 用于兼容旧数据中 $ 直接写在列名里的情况.
     */
    static String cleanMeta(String meta) {
        if (meta == null || meta.isEmpty()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = om.readTree(meta);
            if (!node.has("columns")) {
                return meta;
            }
            com.fasterxml.jackson.databind.JsonNode cols = node.get("columns");
            java.util.List<String> cleanCols = new java.util.ArrayList<>();
            java.util.List<String> formulaCols = new java.util.ArrayList<>();
            boolean changed = false;
            for (com.fasterxml.jackson.databind.JsonNode c : cols) {
                String name = c.asText();
                if (name.startsWith(FORMULA_PREFIX)) {
                    name = name.substring(FORMULA_PREFIX.length());
                    formulaCols.add(name);
                    changed = true;
                }
                cleanCols.add(name);
            }
            // 合并已有的 formulaColumns
            if (node.has("formulaColumns")) {
                for (com.fasterxml.jackson.databind.JsonNode c : node.get("formulaColumns")) {
                    String name = c.asText();
                    if (!formulaCols.contains(name)) {
                        formulaCols.add(name);
                    }
                }
            }
            if (!changed && formulaCols.isEmpty()) {
                return meta;
            }
            StringBuilder sb = new StringBuilder("{\"columns\":[");
            for (int i = 0; i < cleanCols.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(cleanCols.get(i)).append("\"");
            }
            sb.append("]");
            // 保留已有的 formula 字段 (非表格)
            if (node.has("formula")) {
                sb.append(",\"formula\":").append(node.get("formula").asBoolean());
            }
            if (!formulaCols.isEmpty()) {
                sb.append(",\"formulaColumns\":[");
                for (int i = 0; i < formulaCols.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(formulaCols.get(i)).append("\"");
                }
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return meta;
        }
    }

    public Integer getLineRefOrNull() {
        if (!slotCode.startsWith("#")) {
            return null;
        }
        return PageClassifier.parseLineRef(slotCode);
    }

}
