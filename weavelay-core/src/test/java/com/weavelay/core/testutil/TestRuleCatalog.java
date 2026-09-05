package com.weavelay.core.testutil;

import com.weavelay.core.page.DocumentFamily;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.PairMode;
import com.weavelay.core.page.RuleCatalog;
import com.weavelay.core.page.SlotDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TestRuleCatalog implements RuleCatalog {

    private final DocumentFamily family;
    private final List<PageKindDefinition> byPriority;
    private final Map<String, PageKindDefinition> byCode;

    private TestRuleCatalog(
            DocumentFamily family,
            List<PageKindDefinition> byPriority,
            Map<String, PageKindDefinition> byCode) {
        this.family = family;
        this.byPriority = byPriority;
        this.byCode = byCode;
    }

    public static TestRuleCatalog processSpec() {
        Map<String, PageKindDefinition> byCode = new HashMap<String, PageKindDefinition>();
        List<PageKindDefinition> byPriority = new ArrayList<PageKindDefinition>();

        PageRuleConfig catalogRule = new PageRuleConfig(80, 80, "", PairMode.VERTICAL, 80, 120);
        PageKindDefinition catalog = new PageKindDefinition(
                PageKind.PROCESS_CATALOG,
                "工序目录",
                10,
                catalogRule,
                Collections.<SlotDefinition>emptyList());
        byCode.put(catalog.getCode(), catalog);
        byPriority.add(catalog);

        PageRuleConfig coverRule = new PageRuleConfig(80, 80, "", PairMode.HORIZONTAL, 80, 120);
        PageKindDefinition cover = new PageKindDefinition(
                PageKind.COVER,
                "封面",
                60,
                coverRule,
                Collections.<SlotDefinition>emptyList());
        byCode.put(cover.getCode(), cover);
        byPriority.add(cover);

        return new TestRuleCatalog(DocumentFamily.PROCESS_SPEC, byPriority, byCode);
    }

    @Override
    public String getDocumentFamilyCode() {
        return family.getCode();
    }

    @Override
    public String getDocumentFamilyDisplayName() {
        return family.getDisplayName();
    }

    @Override
    public DocumentFamily getDocumentFamily() {
        return family;
    }

    @Override
    public List<PageKindDefinition> getPageKindsByPriority() {
        return byPriority;
    }

    @Override
    public PageKindDefinition getDefinition(PageKind kind) {
        return getDefinitionByCode(kind == null ? PageKind.UNKNOWN.getCode() : kind.getCode());
    }

    @Override
    public PageKindDefinition getDefinitionByCode(String code) {
        PageKindDefinition def = byCode.get(code);
        return def != null ? def : byCode.get(PageKind.UNKNOWN.getCode());
    }
}
