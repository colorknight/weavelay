package com.weavelay.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * 签发台账（SQLite）：记录每把按机 licence，便于追责、防中间商重复要钥。
 */
public final class LicenseLedger implements AutoCloseable {

    private final Connection connection;

    public LicenseLedger(Path dbPath) throws SQLException {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
        } catch (Exception ex) {
            throw new SQLException("cannot create ledger dir", ex);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS license_issue ("
                    + "license_id TEXT PRIMARY KEY,"
                    + "machine_id TEXT NOT NULL,"
                    + "customer TEXT NOT NULL DEFAULT '',"
                    + "dealer_id TEXT NOT NULL DEFAULT '',"
                    + "issued_at TEXT NOT NULL,"
                    + "expires_at TEXT NOT NULL DEFAULT '',"
                    + "features TEXT NOT NULL DEFAULT '',"
                    + "out_path TEXT NOT NULL DEFAULT '',"
                    + "created_at TEXT NOT NULL"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_license_machine ON license_issue(machine_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_license_dealer ON license_issue(dealer_id)");
        }
    }

    public void record(
            String licenseId,
            String machineId,
            String customer,
            String dealerId,
            String issuedAt,
            String expiresAt,
            String features,
            String outPath) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO license_issue(license_id, machine_id, customer, dealer_id, issued_at, "
                        + "expires_at, features, out_path, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, licenseId);
            ps.setString(2, machineId);
            ps.setString(3, customer == null ? "" : customer);
            ps.setString(4, dealerId == null ? "" : dealerId);
            ps.setString(5, issuedAt);
            ps.setString(6, expiresAt == null ? "" : expiresAt);
            ps.setString(7, features == null ? "" : features);
            ps.setString(8, outPath == null ? "" : outPath);
            ps.setString(9, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public int countByMachine(String machineId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM license_issue WHERE machine_id = ?")) {
            ps.setString(1, machineId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int countByDealer(String dealerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM license_issue WHERE dealer_id = ?")) {
            ps.setString(1, dealerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
