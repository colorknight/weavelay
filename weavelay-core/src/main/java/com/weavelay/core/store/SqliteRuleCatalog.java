package com.weavelay.core.store;

import com.weavelay.core.page.DocumentFamily;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.PairMode;
import com.weavelay.core.page.RuleCatalog;
import com.weavelay.core.page.SlotDefinition;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 SQLite 加载页型、合并规则与槽位.
 */
public final class SqliteRuleCatalog implements RuleCatalog {

    private final String familyCode;
    private final String familyDisplayName;
    private final List<PageKindDefinition> byPriority;
    private final Map<String, PageKindDefinition> byCode;

    public SqliteRuleCatalog(WeaveDatabase database, String familyCode) throws SQLException {
        this.familyCode = familyCode;
        this.familyDisplayName = resolveDisplayName(database, familyCode);
        this.byCode = new HashMap<String, PageKindDefinition>();
        this.byPriority = load(database, familyCode, byCode);
    }

    @Override
    public String getDocumentFamilyCode() {
        return familyCode;
    }

    @Override
    public String getDocumentFamilyDisplayName() {
        return familyDisplayName;
    }

    @Override
    public DocumentFamily getDocumentFamily() {
        DocumentFamily known = DocumentFamily.fromCode(familyCode);
        return known == null ? DocumentFamily.PROCESS_SPEC : known;
    }

    @Override
    public List<PageKindDefinition> getPageKindsByPriority() {
        return byPriority;
    }

    @Override
    public PageKindDefinition getDefinition(PageKind kind) {
        if (kind == null) {
            return getDefinitionByCode(PageKind.UNKNOWN.getCode());
        }
        return getDefinitionByCode(kind.getCode());
    }

    @Override
    public PageKindDefinition getDefinitionByCode(String code) {
        PageKindDefinition def = byCode.get(code);
        if (def != null) {
            return def;
        }
        return byCode.get(PageKind.UNKNOWN.getCode());
    }

    private static String resolveDisplayName(WeaveDatabase database, String familyCode) throws SQLException {
        for (FamilyRecord family : database.listFamilies()) {
            if (familyCode.equals(family.getCode())) {
                return family.getDisplayName();
            }
        }
        DocumentFamily known = DocumentFamily.fromCode(familyCode);
        return known == null ? familyCode : known.getDisplayName();
    }

    private static List<PageKindDefinition> load(
            WeaveDatabase database,
            String familyCode,
            Map<String, PageKindDefinition> byCode) throws SQLException {
        List<PageKindDefinition> list = new ArrayList<PageKindDefinition>();
        for (PageKindRow row : database.loadPageKinds(familyCode)) {
            PageRuleConfig rule = new PageRuleConfig(
                    row.yThreshold,
                    row.xThreshold,
                    row.mergeSep,
                    PairMode.fromCode(row.pairMode),
                    row.rowTolerance,
                    row.colTolerance);
            PageKindDefinition def = new PageKindDefinition(
                    row.code,
                    row.displayName,
                    row.classifyPriority,
                    rule,
                    database.loadSlots(row.code));
            byCode.put(row.code, def);
            if (!PageKind.UNKNOWN.getCode().equals(row.code)) {
                list.add(def);
            }
        }
        Collections.sort(list, new Comparator<PageKindDefinition>() {
            @Override
            public int compare(PageKindDefinition a, PageKindDefinition b) {
                return Integer.compare(a.getClassifyPriority(), b.getClassifyPriority());
            }
        });
        return Collections.unmodifiableList(list);
    }
}
