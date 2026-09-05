package com.weavelay.core.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 本地库版本迁移: {@code schema_meta.version} 记录已执行到的版本号.
 * <p>
 * 升级应用时: 在 {@link #MIGRATIONS} 末尾追加新迁移并增大 {@link #CURRENT_VERSION},
 * 不要修改或重排已有迁移.
 */
final class DatabaseMigrator {

    static final int CURRENT_VERSION = 16;

    private static final Logger LOG = Logger.getLogger(DatabaseMigrator.class.getName());

    private static final SchemaMigration[] MIGRATIONS = new SchemaMigration[]{
            new Migration001DocumentFamilyColumns(),
            new Migration002PageKindDescription(),
            new Migration003LineRefAnchors(),
            new Migration004CoverPageRefAnchor(),
            new Migration005ProcessCatalogLayoutAnchors(),
            new Migration006DropLayoutAnchors(),
            new Migration007ProcessCatalogMaterialSlot(),
            new Migration008ProcessCatalogTableColumns(),
            new Migration009FamilyOutput(),
            new Migration010SlotRegionColumns(),
            new Migration011SlotTypeColumns(),
            new Migration012FamilyExcelTemplate(),
            new Migration013FamilyTemplateRule(),
            new Migration014GlobalTemplateRule(),
            new Migration015FamilyOutputDefinitionJson(),
            new Migration016DropPageAnchor(),
    };

    private DatabaseMigrator() {
    }

    static void run(Connection connection) throws SQLException {
        ensureMetaTable(connection);
        int applied = readVersion(connection);
        if (applied > CURRENT_VERSION) {
            LOG.warning("weavelay.db schema version " + applied
                    + " is newer than app supports (" + CURRENT_VERSION + ")");
            return;
        }
        for (SchemaMigration migration : MIGRATIONS) {
            if (migration.version() <= applied) {
                continue;
            }
            if (migration.version() > CURRENT_VERSION) {
                break;
            }
            LOG.info("Applying schema migration v" + migration.version() + ": " + migration.name());
            migration.apply(connection);
            writeVersion(connection, migration.version());
        }
    }

    static int readAppliedVersion(Connection connection) throws SQLException {
        ensureMetaTable(connection);
        return readVersion(connection);
    }

    private static void ensureMetaTable(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_meta ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                    + "version INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at TEXT NOT NULL DEFAULT '')");
            st.execute("INSERT OR IGNORE INTO schema_meta(id, version) VALUES (1, 0)");
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_meta WHERE id = 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE schema_meta SET version = ?, updated_at = datetime('now') WHERE id = 1")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column)
            throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** v1: 文档族扩展字段 + 旧库数据回填. */
    private static final class Migration001DocumentFamilyColumns implements SchemaMigration {

        @Override
        public int version() {
            return 1;
        }

        @Override
        public String name() {
            return "document_family name/english_name/description";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            if (!hasColumn(connection, "document_family", "name")) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE document_family ADD COLUMN name TEXT NOT NULL DEFAULT ''");
                    st.execute("ALTER TABLE document_family ADD COLUMN english_name TEXT NOT NULL DEFAULT ''");
                    st.execute("ALTER TABLE document_family ADD COLUMN description TEXT NOT NULL DEFAULT ''");
                }
            }
            try (Statement st = connection.createStatement()) {
                st.execute("UPDATE document_family SET english_name = display_name "
                        + "WHERE (english_name IS NULL OR english_name = '') "
                        + "AND display_name IS NOT NULL AND display_name <> ''");
                st.execute("UPDATE document_family SET name = '工艺规程' "
                        + "WHERE code = 'process_spec' AND (name IS NULL OR name = '')");
                st.execute("UPDATE document_family SET description = '工艺规程类 PDF 文档' "
                        + "WHERE code = 'process_spec' AND (description IS NULL OR description = '')");
            }
        }
    }

    /** v2: 页型描述列. */
    private static final class Migration002PageKindDescription implements SchemaMigration {

        @Override
        public int version() {
            return 2;
        }

        @Override
        public String name() {
            return "page_kind description column";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            if (!hasColumn(connection, "page_kind", "description")) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE page_kind ADD COLUMN description TEXT NOT NULL DEFAULT ''");
                }
            }
        }
    }

    /** v3: 分类锚点改为行号引用 (#N). */
    private static final class Migration003LineRefAnchors implements SchemaMigration {

        @Override
        public int version() {
            return 3;
        }

        @Override
        public String name() {
            return "page_anchor line-ref classifier (#N)";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            if (!needsLineRefMigration(connection)) {
                return;
            }
            List<String> codes = new ArrayList<String>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT code FROM page_kind WHERE code <> 'unknown'")) {
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
            }
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM page_anchor");
            }
            for (String code : codes) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO page_anchor(page_kind_code, anchor_text) VALUES (?, ?)")) {
                    ps.setString(1, code);
                    ps.setString(2, "#2");
                    ps.executeUpdate();
                }
            }
        }

        private static boolean needsLineRefMigration(Connection connection) throws SQLException {
            if (count(connection, "page_anchor") == 0 && count(connection, "page_kind") > 1) {
                return true;
            }
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT anchor_text FROM page_anchor")) {
                while (rs.next()) {
                    String anchor = rs.getString(1);
                    if (anchor == null) {
                        return true;
                    }
                    String trimmed = anchor.trim();
                    if (!trimmed.matches("#\\d+") && !trimmed.matches("@[+-]?\\d+")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** v4: 封面默认用页号 {@code @1} 而非 {@code #2}. */
    private static final class Migration004CoverPageRefAnchor implements SchemaMigration {

        @Override
        public int version() {
            return 4;
        }

        @Override
        public String name() {
            return "cover default @1 page-ref anchor";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            List<String> coverAnchors = new ArrayList<String>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT anchor_text FROM page_anchor WHERE page_kind_code = 'cover' ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        coverAnchors.add(rs.getString(1));
                    }
                }
            }
            if (coverAnchors.size() != 1 || !"#2".equals(coverAnchors.get(0).trim())) {
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM page_anchor WHERE page_kind_code = 'cover'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO page_anchor(page_kind_code, anchor_text) VALUES ('cover', '@1')")) {
                ps.executeUpdate();
            }
        }
    }

    /** v5: 工序目录改用语义锚点 + table 区域. */
    private static final class Migration005ProcessCatalogLayoutAnchors implements SchemaMigration {

        @Override
        public int version() {
            return 5;
        }

        @Override
        public String name() {
            return "process_catalog ~title and ^table anchors";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            List<String> anchors = new ArrayList<String>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT anchor_text FROM page_anchor WHERE page_kind_code = 'process_catalog' ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        anchors.add(rs.getString(1));
                    }
                }
            }
            if (anchors.size() == 1 && "#2".equals(anchors.get(0).trim())) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM page_anchor WHERE page_kind_code = 'process_catalog'")) {
                    ps.executeUpdate();
                }
                insertAnchor(connection, "process_catalog", "~工序目录");
                insertAnchor(connection, "process_catalog", "^table");
            }
        }

        private static void insertAnchor(Connection connection, String kindCode, String anchor)
                throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO page_anchor(page_kind_code, anchor_text) VALUES (?, ?)")) {
                ps.setString(1, kindCode);
                ps.setString(2, anchor);
                ps.executeUpdate();
            }
        }
    }

    /** v8: 工序目录表体列 (列头锚定, 空值占位). */
    private static final class Migration008ProcessCatalogTableColumns implements SchemaMigration {

        private static final String[][] SLOTS = new String[][]{
                {"tbl_supply_status", "供应状态", "5"},
                {"tbl_blank_type", "毛坯种类", "6"},
                {"tbl_blank_size", "毛坯尺寸", "7"},
                {"tbl_blank_weight", "毛坯重量kg", "8"},
                {"tbl_parts_per_blank", "每一毛坯可制零件数", "9"},
                {"tbl_part_weight", "零件重量kg", "10"},
                {"tbl_parts_per_set", "每套产品零件数", "11"},
        };

        @Override
        public int version() {
            return 8;
        }

        @Override
        public String name() {
            return "process_catalog table column slots";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            for (String[] slot : SLOTS) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR IGNORE INTO slot_definition(page_kind_code, slot_code, slot_label, sort_order) "
                                + "VALUES ('process_catalog', ?, ?, ?)")) {
                    ps.setString(1, slot[0]);
                    ps.setString(2, slot[1]);
                    ps.setInt(3, Integer.parseInt(slot[2]));
                    ps.executeUpdate();
                }
            }
        }
    }

    /** v7: 工序目录增加材料槽 (表体多行单元格). */
    private static final class Migration007ProcessCatalogMaterialSlot implements SchemaMigration {

        @Override
        public int version() {
            return 7;
        }

        @Override
        public String name() {
            return "process_catalog material slot";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO slot_definition(page_kind_code, slot_code, slot_label, sort_order) "
                            + "VALUES ('process_catalog', 'material', '材料', 4)")) {
                ps.executeUpdate();
            }
        }
    }

    /** v6: 移除 layout 依赖的 ^table 锚点. */
    private static final class Migration006DropLayoutAnchors implements SchemaMigration {

        @Override
        public int version() {
            return 6;
        }

        @Override
        public String name() {
            return "drop process_catalog ^table anchor";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM page_anchor WHERE page_kind_code = 'process_catalog' AND anchor_text = '^table'")) {
                ps.executeUpdate();
            }
        }
    }

    /** v9: 文件族级别输出字段配置表. */
    private static final class Migration009FamilyOutput implements SchemaMigration {

        @Override
        public int version() { return 9; }

        @Override
        public String name() { return "family output fields"; }

        @Override
        public void apply(Connection connection) throws SQLException {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS family_output ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, family_code TEXT NOT NULL, "
                        + "field_name TEXT NOT NULL, field_type TEXT NOT NULL DEFAULT 'string', "
                        + "column_headers TEXT DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, "
                        + "UNIQUE(family_code, field_name))");
            }
        }
    }

    /** v10: slot_definition 加区域坐标列 (region_x/y/w/h). */
    private static final class Migration010SlotRegionColumns implements SchemaMigration {
        @Override public int version() { return 10; }
        @Override public String name() { return "slot_definition region columns"; }
        @Override public void apply(Connection connection) throws SQLException {
            if (!hasColumn(connection, "slot_definition", "region_x")) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE slot_definition ADD COLUMN region_x INTEGER DEFAULT 0");
                    st.execute("ALTER TABLE slot_definition ADD COLUMN region_y INTEGER DEFAULT 0");
                    st.execute("ALTER TABLE slot_definition ADD COLUMN region_w INTEGER DEFAULT 0");
                    st.execute("ALTER TABLE slot_definition ADD COLUMN region_h INTEGER DEFAULT 0");
                }
            }
        }
    }

    /** v11: slot_definition 加 field_type / field_meta. */
    private static final class Migration011SlotTypeColumns implements SchemaMigration {
        @Override public int version() { return 11; }
        @Override public String name() { return "slot_definition field_type/meta columns"; }
        @Override public void apply(Connection connection) throws SQLException {
            if (!hasColumn(connection, "slot_definition", "field_type")) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE slot_definition ADD COLUMN field_type TEXT DEFAULT 'text'");
                    st.execute("ALTER TABLE slot_definition ADD COLUMN field_meta TEXT DEFAULT ''");
                }
            }
        }
    }

    /** v12: 文件类型绑定 Excel 输出模板路径（一种文件类型一份模板）。 */
    private static final class Migration012FamilyExcelTemplate implements SchemaMigration {
        @Override
        public int version() {
            return 12;
        }

        @Override
        public String name() {
            return "document_family excel_template_path";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            if (!hasColumn(connection, "document_family", "excel_template_path")) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE document_family "
                            + "ADD COLUMN excel_template_path TEXT NOT NULL DEFAULT ''");
                }
            }
        }
    }

    /** v13: 文件类型输出模板规则（名称 + 正则 → 选用 *模板 Sheet）。 */
    private static final class Migration013FamilyTemplateRule implements SchemaMigration {
        @Override
        public int version() {
            return 13;
        }

        @Override
        public String name() {
            return "family_template_rule";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS family_template_rule ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "family_code TEXT NOT NULL, "
                        + "name TEXT NOT NULL DEFAULT '', "
                        + "regex TEXT NOT NULL DEFAULT '', "
                        + "sort_order INTEGER NOT NULL DEFAULT 0)");
            }
        }
    }

    /** v14: 模板规则改为全局（行业通识），不再挂在文件类型下。 */
    private static final class Migration014GlobalTemplateRule implements SchemaMigration {
        @Override
        public int version() {
            return 14;
        }

        @Override
        public String name() {
            return "global template_rule";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS template_rule ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "name TEXT NOT NULL DEFAULT '', "
                        + "regex TEXT NOT NULL DEFAULT '', "
                        + "sort_order INTEGER NOT NULL DEFAULT 0)");
            }
            // 从旧的按文件类型表迁移去重
            try (Statement st = connection.createStatement()) {
                st.execute("INSERT INTO template_rule(name, regex, sort_order) "
                        + "SELECT name, regex, MIN(sort_order) FROM family_template_rule "
                        + "GROUP BY name, regex");
            } catch (SQLException ignored) {
                // family_template_rule 可能不存在
            }
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS family_template_rule");
            }
            // 空库给两条常见默认规则
            if (count(connection, "template_rule") == 0) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO template_rule(name, regex, sort_order) VALUES (?,?,?)")) {
                    ps.setString(1, "加工");
                    ps.setString(2, "^\\d+$");
                    ps.setInt(3, 10);
                    ps.executeUpdate();
                    ps.setString(1, "检测");
                    ps.setString(2, "Y");
                    ps.setInt(3, 20);
                    ps.executeUpdate();
                }
            }
        }
    }

    /** v15: 文件类型级输出定义 JSON（文档结构：标量/集合/条目补丁等）。 */
    private static final class Migration015FamilyOutputDefinitionJson implements SchemaMigration {
        @Override
        public int version() {
            return 15;
        }

        @Override
        public String name() {
            return "document_family output_definition_json";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            if (!hasColumn(connection, "document_family", "output_definition_json")) {
                try (Statement st = connection.createStatement()) {
                    st.execute("ALTER TABLE document_family "
                            + "ADD COLUMN output_definition_json TEXT NOT NULL DEFAULT ''");
                }
            }
        }
    }

    /** 移除页型自动分类锚点表（页型改为手动选择）. */
    private static final class Migration016DropPageAnchor implements SchemaMigration {
        @Override
        public int version() {
            return 16;
        }

        @Override
        public String name() {
            return "drop page_anchor (auto page-kind classify removed)";
        }

        @Override
        public void apply(Connection connection) throws SQLException {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS page_anchor");
            }
        }
    }
}
