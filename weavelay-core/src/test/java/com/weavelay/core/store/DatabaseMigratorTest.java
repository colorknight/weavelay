package com.weavelay.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    void migrationsAreIdempotent() throws Exception {
        Path db = tempDir.resolve("migrate-test.db");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        try {
            createLegacyV0Schema(conn);
            DatabaseMigrator.run(conn);
            int afterFirst = DatabaseMigrator.readAppliedVersion(conn);
            DatabaseMigrator.run(conn);
            int afterSecond = DatabaseMigrator.readAppliedVersion(conn);
            assertEquals(DatabaseMigrator.CURRENT_VERSION, afterFirst);
            assertEquals(afterFirst, afterSecond);
        } finally {
            conn.close();
        }
    }

    private static void createLegacyV0Schema(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE document_family ("
                    + "code TEXT PRIMARY KEY, display_name TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE page_kind ("
                    + "code TEXT PRIMARY KEY, family_code TEXT NOT NULL, display_name TEXT NOT NULL, "
                    + "classify_priority INTEGER NOT NULL)");
            st.execute("CREATE TABLE page_anchor ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, page_kind_code TEXT NOT NULL, "
                    + "anchor_text TEXT NOT NULL)");
            st.execute("CREATE TABLE page_rule ("
                    + "page_kind_code TEXT PRIMARY KEY, y_threshold REAL NOT NULL, x_threshold REAL NOT NULL, "
                    + "merge_sep TEXT NOT NULL DEFAULT '', pair_mode TEXT NOT NULL, "
                    + "row_tolerance REAL NOT NULL, col_tolerance REAL NOT NULL)");
            st.execute("CREATE TABLE slot_definition ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, page_kind_code TEXT NOT NULL, "
                    + "slot_code TEXT NOT NULL, slot_label TEXT NOT NULL, sort_order INTEGER NOT NULL, "
                    + "UNIQUE(page_kind_code, slot_code))");
            st.execute("INSERT INTO page_kind(code, family_code, display_name, classify_priority) "
                    + "VALUES ('cover', 'process_spec', '封面', 10)");
            st.execute("INSERT INTO page_anchor(page_kind_code, anchor_text) VALUES ('cover', '工艺规程')");
        }
    }
}
