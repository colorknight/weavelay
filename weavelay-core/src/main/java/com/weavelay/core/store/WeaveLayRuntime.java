package com.weavelay.core.store;

import com.weavelay.core.extract.PageExtractService;
import com.weavelay.core.page.DocumentFamily;

import java.sql.SQLException;
import java.util.List;

/**
 * 打开本地库并构建页解析服务.
 */
public final class WeaveLayRuntime {

    private static volatile WeaveLayRuntime instance;

    private final WeaveDatabase database;
    private String familyCode;
    private RuleCatalogHolder holder;

    private WeaveLayRuntime(WeaveDatabase database) throws SQLException {
        this.database = database;
        this.familyCode = DocumentFamily.PROCESS_SPEC.getCode();
        reloadCatalog();
    }

    public static WeaveLayRuntime open() throws SQLException {
        if (instance != null) {
            return instance;
        }
        synchronized (WeaveLayRuntime.class) {
            if (instance == null) {
                instance = new WeaveLayRuntime(WeaveDatabase.openDefault());
            }
            return instance;
        }
    }

    public WeaveDatabase getDatabase() {
        return database;
    }

    public String getFamilyCode() {
        return familyCode;
    }

    public synchronized void selectFamily(String code) throws SQLException {
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        this.familyCode = code.trim();
        reloadCatalog();
    }

    public synchronized void reloadCatalog() throws SQLException {
        this.holder = new RuleCatalogHolder(new SqliteRuleCatalog(database, familyCode));
    }

    public PageExtractService getPageExtractService() {
        return holder.pageExtractService;
    }

    public List<FamilyRecord> listFamilies() throws SQLException {
        return database.listFamilies();
    }

    private static final class RuleCatalogHolder {
        private final PageExtractService pageExtractService;

        private RuleCatalogHolder(SqliteRuleCatalog catalog) {
            this.pageExtractService = new PageExtractService(catalog);
        }
    }
}
