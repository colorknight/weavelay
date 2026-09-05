package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.RuleCatalog;
import com.weavelay.core.store.PageKindRecord;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按页型路由解析器（页型由调用方指定；未指定则为未识别）.
 */
public final class PageExtractService {

    private final RuleCatalog catalog;
    private final Map<PageKind, PageExtractor> extractors;
    private final PageExtractor fallback;

    public PageExtractService(RuleCatalog catalog) {
        this.catalog = catalog;
        this.extractors = new EnumMap<PageKind, PageExtractor>(PageKind.class);
        register(new CoverPageExtractor());
        register(new ProcessCatalogPageExtractor());
        this.fallback = new MergeFallbackExtractor();
    }

    public RuleCatalog getCatalog() {
        return catalog;
    }

    public String getDocumentFamilyCode() {
        return catalog.getDocumentFamilyCode();
    }

    public String getDocumentFamilyDisplayName() {
        return catalog.getDocumentFamilyDisplayName();
    }

    public PageExtractResult processPage(String streamName, List<OcrRow> pageRows) {
        return processPage(streamName, pageRows, 1, 1, null);
    }

    public PageExtractResult processPage(String streamName, List<OcrRow> pageRows, String forcedPageKindCode) {
        return processPage(streamName, pageRows, 1, 1, forcedPageKindCode);
    }

    public PageExtractResult processPage(
            String streamName,
            List<OcrRow> pageRows,
            int pageIndex,
            int totalPages) {
        return processPage(streamName, pageRows, pageIndex, totalPages, null);
    }

    public PageExtractResult processPage(
            String streamName,
            List<OcrRow> pageRows,
            int pageIndex,
            int totalPages,
            String forcedPageKindCode) {
        String kindCode = forcedPageKindCode;
        if (kindCode == null || kindCode.trim().isEmpty()) {
            kindCode = PageKind.UNKNOWN.getCode();
        }
        PageKindDefinition definition = catalog.getDefinitionByCode(kindCode);
        if (definition == null) {
            definition = catalog.getDefinitionByCode(PageKind.UNKNOWN.getCode());
        }
        PageKind kind = PageKind.fromCode(kindCode);
        PageExtractor extractor = extractors.get(kind);
        if (extractor == null) {
            extractor = fallback;
        }
        return extractor.extract(streamName, pageRows, definition);
    }

    public String pageKindDisplayName(String kindCode) {
        PageKindDefinition def = catalog.getDefinitionByCode(kindCode);
        if (def != null) {
            return def.getDisplayName();
        }
        return "未识别";
    }

    public String pageKindDisplayName(PageKind kind) {
        return pageKindDisplayName(kind == null ? PageKind.UNKNOWN.getCode() : kind.getCode());
    }

    public List<PageKindRecord> listSelectablePageKinds() {
        List<PageKindRecord> list = new ArrayList<PageKindRecord>();
        for (PageKindDefinition def : catalog.getPageKindsByPriority()) {
            if (PageKind.UNKNOWN.getCode().equals(def.getCode())) {
                continue;
            }
            list.add(new PageKindRecord(
                    def.getCode(),
                    def.getDisplayName(),
                    catalog.getDocumentFamilyCode(),
                    def.getClassifyPriority()));
        }
        return list;
    }

    private void register(PageExtractor extractor) {
        extractors.put(extractor.supportedKind(), extractor);
    }
}
