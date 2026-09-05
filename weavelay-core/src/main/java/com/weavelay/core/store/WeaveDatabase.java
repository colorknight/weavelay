package com.weavelay.core.store;

import com.weavelay.core.page.DocumentFamily;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.PairMode;
import com.weavelay.core.page.SlotDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 本地 SQLite: 文档族、页型锚点、规则集、槽位定义.
 */
public final class WeaveDatabase {

    private static final Logger LOG = Logger.getLogger(WeaveDatabase.class.getName());
    private static volatile WeaveDatabase instance;

    private final Path dbPath;
    private final Connection connection;

    private WeaveDatabase(Path dbPath, Connection connection) {
        this.dbPath = dbPath;
        this.connection = connection;
    }

    public static synchronized WeaveDatabase openDefault() throws SQLException {
        if (instance != null) {
            return instance;
        }
        Path dir = com.weavelay.core.WeavelayDataDir.get();
        Path db = dir.resolve("weavelay.db");
        WeaveDatabase dbInstance = open(db);
        instance = dbInstance;
        return instance;
    }

    /**
     * 打开指定路径的库（不注册为全局单例）。用于测试与工具；调用方负责 {@link #close()}。
     */
    public static WeaveDatabase open(Path dbPath) throws SQLException {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath required");
        }
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (java.io.IOException ex) {
            throw new SQLException("cannot create db parent: " + dbPath, ex);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        conn.setAutoCommit(true);
        WeaveDatabase dbInstance = new WeaveDatabase(dbPath, conn);
        dbInstance.initSchema();
        return dbInstance;
    }

    public Path getDbPath() {
        return dbPath;
    }

    public Connection getConnection() {
        return connection;
    }

    /** 关闭非单例连接。默认库请勿调用。 */
    public void close() throws SQLException {
        if (this == instance) {
            throw new IllegalStateException("refusing to close default WeaveDatabase instance");
        }
        connection.close();
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS document_family ("
                    + "code TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL DEFAULT '', "
                    + "english_name TEXT NOT NULL DEFAULT '', "
                    + "description TEXT NOT NULL DEFAULT '', "
                    + "display_name TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE IF NOT EXISTS page_kind ("
                    + "code TEXT PRIMARY KEY, family_code TEXT NOT NULL, display_name TEXT NOT NULL, "
                    + "description TEXT NOT NULL DEFAULT '', "
                    + "classify_priority INTEGER NOT NULL)");
            // page_anchor 仅供历史迁移 003–006 使用；Migration016 会 DROP
            st.execute("CREATE TABLE IF NOT EXISTS page_anchor ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, page_kind_code TEXT NOT NULL, "
                    + "anchor_text TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS page_rule ("
                    + "page_kind_code TEXT PRIMARY KEY, y_threshold REAL NOT NULL, x_threshold REAL NOT NULL, "
                    + "merge_sep TEXT NOT NULL DEFAULT '', pair_mode TEXT NOT NULL, "
                    + "row_tolerance REAL NOT NULL, col_tolerance REAL NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS slot_definition ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, page_kind_code TEXT NOT NULL, "
                    + "slot_code TEXT NOT NULL, slot_label TEXT NOT NULL, sort_order INTEGER NOT NULL, "
                    + "region_x INTEGER DEFAULT 0, region_y INTEGER DEFAULT 0, "
                    + "region_w INTEGER DEFAULT 0, region_h INTEGER DEFAULT 0, "
                    + "field_type TEXT DEFAULT 'text', field_meta TEXT DEFAULT '', "
                    + "UNIQUE(page_kind_code, slot_code))");
            st.execute("CREATE TABLE IF NOT EXISTS family_output ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, family_code TEXT NOT NULL, "
                    + "field_name TEXT NOT NULL, field_type TEXT NOT NULL DEFAULT 'string', "
                    + "column_headers TEXT DEFAULT '', sort_order INTEGER NOT NULL DEFAULT 0, "
                    + "UNIQUE(family_code, field_name))");
            st.execute("CREATE TABLE IF NOT EXISTS schema_meta ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                    + "version INTEGER NOT NULL DEFAULT 0, "
                    + "updated_at TEXT NOT NULL DEFAULT '')");
        }
        // 先种子再迁移：部分迁移会对 process_catalog 做 INSERT OR IGNORE；
        // 若先迁移再种子，种子里的同名槽位会撞 UNIQUE。
        seedIfEmpty();
        DatabaseMigrator.run(connection);
    }

    /** 当前本地库 schema 版本 (已执行迁移号). */
    public int getSchemaVersion() throws SQLException {
        return DatabaseMigrator.readAppliedVersion(connection);
    }

    private void seedIfEmpty() throws SQLException {
        if (count("page_kind") > 0) {
            return;
        }
        LOG.info("Seeding weavelay.db with process_spec rules");
        seedProcessSpec();
    }

    public List<FamilyRecord> listFamilies() throws SQLException {
        List<FamilyRecord> list = new ArrayList<FamilyRecord>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT code, name, english_name, description, excel_template_path "
                             + "FROM document_family ORDER BY name, english_name")) {
            while (rs.next()) {
                list.add(new FamilyRecord(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5)));
            }
        }
        return list;
    }

    public List<PageKindRecord> listPageKinds(String familyCode) throws SQLException {
        List<PageKindRecord> list = new ArrayList<PageKindRecord>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT code, display_name, description, family_code, classify_priority FROM page_kind "
                        + "WHERE family_code = ? AND code <> 'unknown' ORDER BY classify_priority")) {
            ps.setString(1, familyCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PageKindRecord(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getInt(5)));
                }
            }
        }
        return list;
    }

    public void addDocumentFamily(
            String code,
            String name,
            String englishName,
            String description) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_family(code, name, english_name, description, display_name) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, englishName);
            ps.setString(4, description == null ? "" : description);
            ps.setString(5, englishName);
            ps.executeUpdate();
        }
    }

    public void deleteDocumentFamily(String code) throws SQLException {
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        List<String> pageKindCodes = new ArrayList<String>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT code FROM page_kind WHERE family_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pageKindCodes.add(rs.getString(1));
                }
            }
        }
        for (String pageKindCode : pageKindCodes) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM slot_definition WHERE page_kind_code = ?")) {
                ps.setString(1, pageKindCode);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM page_rule WHERE page_kind_code = ?")) {
                ps.setString(1, pageKindCode);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM page_kind WHERE family_code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM family_output WHERE family_code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM document_family WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        }
    }

    public FamilyRecord findFamily(String familyCode) throws SQLException {
        if (familyCode == null || familyCode.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT code, name, english_name, description, excel_template_path "
                        + "FROM document_family WHERE code = ?")) {
            ps.setString(1, familyCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new FamilyRecord(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5));
                }
            }
        }
        return null;
    }

    public boolean familyExists(String familyCode) throws SQLException {
        return findFamily(familyCode) != null;
    }

    /** 页面类型所属文件类型；不存在返回 null。 */
    public String findPageKindFamilyCode(String pageKindCode) throws SQLException {
        if (pageKindCode == null || pageKindCode.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT family_code FROM page_kind WHERE code = ?")) {
            ps.setString(1, pageKindCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return null;
    }

    /** 含 unknown 在内的全部页面类型（导出模板包用）。 */
    public List<PageKindRecord> listAllPageKinds(String familyCode) throws SQLException {
        List<PageKindRecord> list = new ArrayList<PageKindRecord>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT code, display_name, description, family_code, classify_priority FROM page_kind "
                        + "WHERE family_code = ? ORDER BY classify_priority")) {
            ps.setString(1, familyCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PageKindRecord(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getInt(5)));
                }
            }
        }
        return list;
    }

    public PageRuleConfig loadPageRule(String pageKindCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT y_threshold, x_threshold, merge_sep, pair_mode, row_tolerance, col_tolerance "
                        + "FROM page_rule WHERE page_kind_code = ?")) {
            ps.setString(1, pageKindCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PageRuleConfig(
                            rs.getDouble(1),
                            rs.getDouble(2),
                            rs.getString(3),
                            PairMode.fromCode(rs.getString(4)),
                            rs.getDouble(5),
                            rs.getDouble(6));
                }
            }
        }
        return new PageRuleConfig(80, 80, "", PairMode.NONE, 80, 120);
    }

    /**
     * 导入用：写入页面类型及其规则、槽位（含坐标）。调用前需保证 code 未被其他文件类型占用。
     */
    public void insertImportedPageKind(
            String familyCode,
            String code,
            String displayName,
            String description,
            int classifyPriority,
            PageRuleConfig rule,
            List<SlotDefinition> slots) throws SQLException {
        if (familyCode == null || code == null || familyCode.isBlank() || code.isBlank()) {
            return;
        }
        PageRuleConfig r = rule == null
                ? new PageRuleConfig(80, 80, "", PairMode.NONE, 80, 120)
                : rule;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page_kind(code, family_code, display_name, description, classify_priority) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, code.trim());
            ps.setString(2, familyCode.trim());
            ps.setString(3, displayName == null ? "" : displayName);
            ps.setString(4, description == null ? "" : description);
            ps.setInt(5, classifyPriority);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page_rule(page_kind_code, y_threshold, x_threshold, merge_sep, pair_mode, "
                        + "row_tolerance, col_tolerance) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, code.trim());
            ps.setDouble(2, r.getYThreshold());
            ps.setDouble(3, r.getXThreshold());
            ps.setString(4, r.getMergeSep());
            ps.setString(5, r.getPairMode().name());
            ps.setDouble(6, r.getRowTolerance());
            ps.setDouble(7, r.getColTolerance());
            ps.executeUpdate();
        }
        replaceSlotDefinitions(code.trim(), slots);
    }

    public void updateDocumentFamily(String code, String name, String englishName, String description)
            throws SQLException {
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_family SET name=?, english_name=?, description=?, display_name=? WHERE code=?")) {
            ps.setString(1, name);
            ps.setString(2, englishName);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, englishName);
            ps.setString(5, code);
            ps.executeUpdate();
        }
    }

    /** 一种文件类型对应一份 Excel 输出模板；空字符串表示清除。 */
    public void updateDocumentFamilyExcelTemplate(String code, String excelTemplatePath)
            throws SQLException {
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_family SET excel_template_path=? WHERE code=?")) {
            ps.setString(1, excelTemplatePath == null ? "" : excelTemplatePath.trim());
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    /** 文件类型级输出定义 JSON；空表示未配置。 */
    public String getFamilyOutputDefinitionJson(String familyCode) throws SQLException {
        if (familyCode == null || familyCode.isBlank()) {
            return "";
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT output_definition_json FROM document_family WHERE code=?")) {
            ps.setString(1, familyCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    return json == null ? "" : json;
                }
            }
        }
        return "";
    }

    public void saveFamilyOutputDefinitionJson(String familyCode, String json) throws SQLException {
        if (familyCode == null || familyCode.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE document_family SET output_definition_json=? WHERE code=?")) {
            ps.setString(1, json == null ? "" : json);
            ps.setString(2, familyCode.trim());
            ps.executeUpdate();
        }
    }

    public void addPageKind(
            String familyCode,
            String code,
            String displayName,
            String description,
            String pairMode) throws SQLException {
        insertPageKind(
                code,
                displayName,
                description,
                nextPageKindPriority(familyCode),
                80,
                80,
                "",
                pairMode == null ? "NONE" : pairMode,
                80,
                120,
                new String[][]{},
                familyCode);
    }

    public void swapPageKindOrder(String familyCode, String pageKindCode, boolean moveUp) throws SQLException {
        List<PageKindRecord> kinds = listPageKinds(familyCode);
        int index = -1;
        for (int i = 0; i < kinds.size(); i++) {
            if (pageKindCode.equals(kinds.get(i).getCode())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        int swapIndex = moveUp ? index - 1 : index + 1;
        if (swapIndex < 0 || swapIndex >= kinds.size()) {
            return;
        }
        swapPageKindPriorities(kinds.get(index).getCode(), kinds.get(swapIndex).getCode());
    }

    /**
     * 交换两个页面类型顺序：先交换列表位置，再按 10,20,30… 重写 classify_priority，
     * 避免同优先级时「交换无效」、以及 ORDER BY 不稳定。
     */
    public void swapPageKindPriorities(String codeA, String codeB) throws SQLException {
        if (codeA == null || codeB == null || codeA.equals(codeB)) {
            return;
        }
        String familyCode = null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT family_code FROM page_kind WHERE code = ?")) {
            ps.setString(1, codeA);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    familyCode = rs.getString(1);
                }
            }
        }
        if (familyCode == null) {
            return;
        }
        List<PageKindRecord> kinds = listPageKinds(familyCode);
        int indexA = -1;
        int indexB = -1;
        for (int i = 0; i < kinds.size(); i++) {
            String code = kinds.get(i).getCode();
            if (codeA.equals(code)) {
                indexA = i;
            } else if (codeB.equals(code)) {
                indexB = i;
            }
        }
        if (indexA < 0 || indexB < 0) {
            return;
        }
        java.util.Collections.swap(kinds, indexA, indexB);
        for (int i = 0; i < kinds.size(); i++) {
            updateClassifyPriority(kinds.get(i).getCode(), (i + 1) * 10);
        }
    }

    private int nextPageKindPriority(String familyCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(classify_priority) FROM page_kind "
                        + "WHERE family_code = ? AND code <> 'unknown'")) {
            ps.setString(1, familyCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !rs.wasNull()) {
                    return rs.getInt(1) + 10;
                }
            }
        }
        return 10;
    }

    private void updateClassifyPriority(String pageKindCode, int priority) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE page_kind SET classify_priority = ? WHERE code = ?")) {
            ps.setInt(1, priority);
            ps.setString(2, pageKindCode);
            ps.executeUpdate();
        }
    }

    public DocumentFamily resolveFamily(String familyCode) throws SQLException {
        if (familyCode == null) {
            return DocumentFamily.PROCESS_SPEC;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT code, display_name FROM document_family WHERE code = ?")) {
            ps.setString(1, familyCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    DocumentFamily known = DocumentFamily.fromCode(rs.getString(1));
                    if (known != null) {
                        return known;
                    }
                    return DocumentFamily.PROCESS_SPEC;
                }
            }
        }
        return DocumentFamily.PROCESS_SPEC;
    }

    private int count(String table) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void seedProcessSpec() throws SQLException {
        insertFamily(
                DocumentFamily.PROCESS_SPEC.getCode(),
                DocumentFamily.PROCESS_SPEC.getName(),
                DocumentFamily.PROCESS_SPEC.getEnglishName(),
                DocumentFamily.PROCESS_SPEC.getDescription());

        insertPageKind("process_catalog", "工序目录", "列出全部工序的目录页", 10,
                80, 80, "", "VERTICAL", 80, 120,
                new String[][]{
                        {"product_code", "产品代号", "1"},
                        {"part_name", "零部件名称", "2"},
                        {"part_code", "零部件代号", "3"},
                        {"material", "材料", "4"},
                        {"tbl_supply_status", "供应状态", "5"},
                        {"tbl_blank_type", "毛坯种类", "6"},
                        {"tbl_blank_size", "毛坯尺寸", "7"},
                        {"tbl_blank_weight", "毛坯重量kg", "8"},
                        {"tbl_parts_per_blank", "每一毛坯可制零件数", "9"},
                        {"tbl_part_weight", "零件重量kg", "10"},
                        {"tbl_parts_per_set", "每套产品零件数", "11"}
                },
                DocumentFamily.PROCESS_SPEC.getCode());

        insertPageKind("process_drawing", "工艺附图", "工艺说明附图页", 20,
                80, 80, "", "NONE", 80, 120,
                new String[][]{},
                DocumentFamily.PROCESS_SPEC.getCode());

        insertPageKind("machining_card", "机械加工工序卡片", "机械加工工序卡片页", 30,
                80, 80, "", "NONE", 80, 120,
                new String[][]{},
                DocumentFamily.PROCESS_SPEC.getCode());

        insertPageKind("inspection_card", "检验卡片", "检验工序卡片页", 40,
                80, 80, "", "NONE", 80, 120,
                new String[][]{},
                DocumentFamily.PROCESS_SPEC.getCode());

        insertPageKind("auxiliary_card", "辅助工序卡片", "辅助工序卡片页", 50,
                80, 80, "", "NONE", 80, 120,
                new String[][]{},
                DocumentFamily.PROCESS_SPEC.getCode());

        insertPageKind("cover", "封面", "工艺规程封面页", 60,
                80, 80, "", "HORIZONTAL", 80, 120,
                new String[][]{
                        {"product_code", "产品代号", "1"},
                        {"part_name", "零部件名称", "2"},
                        {"part_code", "零部件代号", "3"}
                },
                DocumentFamily.PROCESS_SPEC.getCode());

        insertPageKind("unknown", "未识别", "", 999,
                80, 350, "", "NONE", 80, 120,
                new String[][]{},
                DocumentFamily.PROCESS_SPEC.getCode());

    }

    private void insertFamily(String code, String name, String englishName, String description)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_family(code, name, english_name, description, display_name) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, englishName);
            ps.setString(4, description);
            ps.setString(5, englishName);
            ps.executeUpdate();
        }
    }

    private void insertPageKind(
            String code,
            String displayName,
            String description,
            int priority,
            double yThreshold,
            double xThreshold,
            String mergeSep,
            String pairMode,
            double rowTolerance,
            double colTolerance,
            String[][] slots,
            String familyCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page_kind(code, family_code, display_name, description, classify_priority) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, familyCode);
            ps.setString(3, displayName);
            ps.setString(4, description == null ? "" : description);
            ps.setInt(5, priority);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO page_rule(page_kind_code, y_threshold, x_threshold, merge_sep, pair_mode, "
                        + "row_tolerance, col_tolerance) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, code);
            ps.setDouble(2, yThreshold);
            ps.setDouble(3, xThreshold);
            ps.setString(4, mergeSep);
            ps.setString(5, pairMode);
            ps.setDouble(6, rowTolerance);
            ps.setDouble(7, colTolerance);
            ps.executeUpdate();
        }
        if (slots != null) {
            for (String[] slot : slots) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO slot_definition(page_kind_code, slot_code, slot_label, sort_order) "
                                + "VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, code);
                    ps.setString(2, slot[0]);
                    ps.setString(3, slot[1]);
                    ps.setInt(4, Integer.parseInt(slot[2]));
                    ps.executeUpdate();
                }
            }
        }
    }

    public String getPairMode(String pageKindCode) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pair_mode FROM page_rule WHERE page_kind_code = ?")) {
            ps.setString(1, pageKindCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        return "NONE";
    }

    List<PageKindRow> loadPageKinds(String familyCode) throws SQLException {
        List<PageKindRow> rows = new ArrayList<PageKindRow>();
        String sql = "SELECT pk.code, pk.display_name, pk.classify_priority, "
                + "pr.y_threshold, pr.x_threshold, pr.merge_sep, pr.pair_mode, "
                + "pr.row_tolerance, pr.col_tolerance "
                + "FROM page_kind pk JOIN page_rule pr ON pk.code = pr.page_kind_code "
                + "WHERE pk.family_code = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, familyCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PageKindRow(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getInt(3),
                            rs.getDouble(4),
                            rs.getDouble(5),
                            rs.getString(6),
                            rs.getString(7),
                            rs.getDouble(8),
                            rs.getDouble(9)));
                }
            }
        }
        return rows;
    }

    public List<SlotDefinition> listSlots(String pageKindCode) throws SQLException {
        return loadSlots(pageKindCode);
    }

    public void updatePageKindMeta(String pageKindCode, String displayName, String description)
            throws SQLException {
        if (pageKindCode == null || pageKindCode.trim().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE page_kind SET display_name = ?, description = ? WHERE code = ?")) {
            ps.setString(1, displayName == null ? "" : displayName.trim());
            ps.setString(2, description == null ? "" : description.trim());
            ps.setString(3, pageKindCode);
            ps.executeUpdate();
        }
    }

    /** 按 slot_code 更新单个槽位的坐标（不删不插，精确更新）。 */
    public void updateSlotRegion(String pageKindCode, String slotCode,
                                  int regionX, int regionY, int regionW, int regionH) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE slot_definition SET region_x=?, region_y=?, region_w=?, region_h=? "
                        + "WHERE page_kind_code=? AND slot_code=?")) {
            ps.setInt(1, regionX); ps.setInt(2, regionY);
            ps.setInt(3, regionW); ps.setInt(4, regionH);
            ps.setString(5, pageKindCode); ps.setString(6, slotCode);
            ps.executeUpdate();
        }
    }

    /** 添加单个槽位到页面类型。 */
    public void addSlot(String pageKindCode, String slotCode, String slotLabel, int sortOrder,
                        int regionX, int regionY, int regionW, int regionH) throws SQLException {
        addSlot(pageKindCode, slotCode, slotLabel, sortOrder, regionX, regionY, regionW, regionH, "text", "");
    }

    public void addSlot(String pageKindCode, String slotCode, String slotLabel, int sortOrder,
                        int regionX, int regionY, int regionW, int regionH,
                        String fieldType, String fieldMeta) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO slot_definition(page_kind_code, slot_code, slot_label, sort_order, "
                        + "region_x, region_y, region_w, region_h, field_type, field_meta) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, pageKindCode); ps.setString(2, slotCode);
            ps.setString(3, slotLabel); ps.setInt(4, sortOrder);
            ps.setInt(5, regionX); ps.setInt(6, regionY);
            ps.setInt(7, regionW); ps.setInt(8, regionH);
            ps.setString(9, fieldType); ps.setString(10, fieldMeta);
            ps.executeUpdate();
        }
    }

    public void replaceSlotDefinitions(String pageKindCode, List<SlotDefinition> slots) throws SQLException {
        if (pageKindCode == null || pageKindCode.trim().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM slot_definition WHERE page_kind_code = ?")) {
            ps.setString(1, pageKindCode);
            ps.executeUpdate();
        }
        if (slots == null || slots.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO slot_definition(page_kind_code, slot_code, slot_label, sort_order, "
                        + "region_x, region_y, region_w, region_h, field_type, field_meta) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (SlotDefinition slot : slots) {
                ps.setString(1, pageKindCode);
                ps.setString(2, slot.getSlotCode());
                ps.setString(3, slot.getSlotLabel());
                ps.setInt(4, slot.getSortOrder());
                ps.setInt(5, slot.getRegionX()); ps.setInt(6, slot.getRegionY());
                ps.setInt(7, slot.getRegionW()); ps.setInt(8, slot.getRegionH());
                ps.setString(9, slot.getFieldType());
                ps.setString(10, slot.getFieldMeta());
                ps.executeUpdate();
            }
        }
    }

    /** 保存文件族的输出字段配置. */
    public void saveFamilyOutputFields(String familyCode,
                                        List<String> fieldNames,
                                        List<String> fieldTypes,
                                        List<String> columnHeaders) throws SQLException {
        if (familyCode == null || familyCode.trim().isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM family_output WHERE family_code = ?")) {
            ps.setString(1, familyCode);
            ps.executeUpdate();
        }
        if (fieldNames == null || fieldNames.isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO family_output(family_code, field_name, field_type, column_headers, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < fieldNames.size(); i++) {
                ps.setString(1, familyCode);
                ps.setString(2, fieldNames.get(i));
                ps.setString(3, fieldTypes.get(i));
                ps.setString(4, columnHeaders != null && i < columnHeaders.size()
                        ? columnHeaders.get(i) : "");
                ps.setInt(5, i);
                ps.executeUpdate();
            }
        }
    }

    /** 加载文件族的输出字段配置. 返回 {fieldName, fieldType, columnHeaders} 列表. */
    public List<String[]> loadFamilyOutputFields(String familyCode) throws SQLException {
        List<String[]> result = new ArrayList<>();
        if (familyCode == null) return result;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT field_name, field_type, column_headers FROM family_output "
                        + "WHERE family_code = ? ORDER BY sort_order")) {
            ps.setString(1, familyCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new String[]{
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3)
                    });
                }
            }
        }
        return result;
    }

    public List<SlotDefinition> loadSlots(String pageKindCode) throws SQLException {
        List<SlotDefinition> slots = new ArrayList<SlotDefinition>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT slot_code, slot_label, sort_order, "
                        + "COALESCE(region_x,0), COALESCE(region_y,0), "
                        + "COALESCE(region_w,0), COALESCE(region_h,0), "
                        + "COALESCE(field_type,'text'), COALESCE(field_meta,'') "
                        + "FROM slot_definition WHERE page_kind_code = ? ORDER BY sort_order")) {
            ps.setString(1, pageKindCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    slots.add(new SlotDefinition(
                            rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                            rs.getString(8), rs.getString(9)));
                }
            }
        }
        return slots;
    }
}
