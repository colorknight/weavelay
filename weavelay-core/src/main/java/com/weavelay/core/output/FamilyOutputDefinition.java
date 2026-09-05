package com.weavelay.core.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件类型级输出定义（存库 JSON）。
 * <p>
 * 层次：根实体 → 标量属性；根实体 → 集合[]（条目属性 + 可选卡片）；页写入指向路径。
 */
public final class FamilyOutputDefinition {

    public int version = 2;
    /** 根实体名，如「零件」「产品」。 */
    public String root = "零件";
    /** 根上的标量属性名。 */
    public List<String> product = new ArrayList<>();
    /**
     * 集合名 → 定义。键是逻辑名（工序），可与来源表名不同。
     */
    public LinkedHashMap<String, CollectionDef> collections = new LinkedHashMap<>();
    /**
     * 兼容旧版：表名 → 列。加载旧 JSON 仍可读；保存时由 collections 同步生成。
     */
    public LinkedHashMap<String, TableDef> tables = new LinkedHashMap<>();
    public List<PageWriteDef> pageWrites = new ArrayList<>();

    public static final class CollectionDef {
        /** 识别/OCR 表名，如「工序目录表」；空则用集合名。 */
        public String sourceTable = "";
        public List<String> columns = new ArrayList<>();
        public String matchKey = "";
        public String entryId = "";
        /** 条目下从属卡片名 → 卡片字段（可空，仅占位声明从属）。 */
        public LinkedHashMap<String, CardDef> cards = new LinkedHashMap<>();
    }

    public static final class CardDef {
        public List<String> fields = new ArrayList<>();
    }

    /** @deprecated 用 {@link CollectionDef}；保留给旧 JSON。 */
    public static final class TableDef {
        public List<String> columns = new ArrayList<>();
        public String matchKey = "";
        public String entryId = "";
    }

    public static final class PageWriteDef {
        public String pageKind = "";
        /** setScalars | mergeTable | patchEntry */
        public String mode = "setScalars";
        /**
         * 写入路径，如 零件 / 零件.工序 / 零件.工序.机加工序
         */
        public String target = "";
        public String matchKey = "";
        public String card = "";
        /** 逻辑集合名（可选，缺省从 target 解析）。 */
        public String collection = "";
    }

    /** 把 collections 同步到 tables，便于旧逻辑按表名查找。 */
    public void syncTablesFromCollections() {
        LinkedHashMap<String, TableDef> next = new LinkedHashMap<>();
        if (collections != null) {
            for (Map.Entry<String, CollectionDef> e : collections.entrySet()) {
                CollectionDef c = e.getValue();
                if (c == null) {
                    continue;
                }
                String tableName = (c.sourceTable != null && !c.sourceTable.isBlank())
                        ? c.sourceTable.trim() : e.getKey();
                TableDef t = new TableDef();
                if (c.columns != null) {
                    t.columns = new ArrayList<>(c.columns);
                }
                t.matchKey = c.matchKey == null ? "" : c.matchKey;
                t.entryId = c.entryId == null || c.entryId.isBlank() ? t.matchKey : c.entryId;
                next.put(tableName, t);
            }
        }
        this.tables = next;
    }

    /** 旧 JSON 只有 tables 时，升成 collections。 */
    public void migrateTablesToCollections() {
        if (collections == null) {
            collections = new LinkedHashMap<>();
        }
        if (!collections.isEmpty() || tables == null || tables.isEmpty()) {
            return;
        }
        for (Map.Entry<String, TableDef> e : tables.entrySet()) {
            String name = e.getKey();
            TableDef t = e.getValue() == null ? new TableDef() : e.getValue();
            CollectionDef c = new CollectionDef();
            c.sourceTable = name;
            c.columns = t.columns == null ? new ArrayList<>() : new ArrayList<>(t.columns);
            c.matchKey = t.matchKey == null ? "" : t.matchKey;
            c.entryId = t.entryId == null || t.entryId.isBlank() ? c.matchKey : t.entryId;
            // 集合逻辑名：去掉常见「表」后缀
            String logical = name.endsWith("表") && name.length() > 1
                    ? name.substring(0, name.length() - 1) : name;
            collections.put(logical, c);
        }
    }
}
