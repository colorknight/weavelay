package com.weavelay.core.page;

import java.util.List;

/**
 * 文档族下的页型与规则目录.
 */
public interface RuleCatalog {

    String getDocumentFamilyCode();

    String getDocumentFamilyDisplayName();

    DocumentFamily getDocumentFamily();

    List<PageKindDefinition> getPageKindsByPriority();

    PageKindDefinition getDefinition(PageKind kind);

    PageKindDefinition getDefinitionByCode(String code);
}
