package com.weavelay.core.model;

import com.weavelay.core.extract.PageExtractResult;
import com.weavelay.core.extract.PageExtractService;
import com.weavelay.core.page.DocumentFamily;
import com.weavelay.core.page.PageKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单页 OCR + 分类 + 槽位 + 织版行.
 */
public final class WeavePageResult {

    private final String streamName;
    private final int pageNumber;
    private final byte[] pagePng;
    private final List<OcrRow> ocrRows;
    private final DocumentFamily documentFamily;
    private final String documentFamilyCode;
    private final String documentFamilyName;
    private final PageKind pageKind;
    private final String pageKindCode;
    private final String pageKindDisplayName;
    private final boolean manualPageKind;
    private final List<SlotValue> slotValues;
    private final List<WeaveRow> pageWeaveRows;

    public WeavePageResult(String streamName, int pageNumber, byte[] pagePng, List<OcrRow> ocrRows) {
        this(streamName, pageNumber, pagePng, ocrRows,
                null, "", "",
                PageKind.UNKNOWN, PageKind.UNKNOWN.getCode(), "未识别", false,
                Collections.<SlotValue>emptyList(),
                Collections.<WeaveRow>emptyList());
    }

    public WeavePageResult(
            String streamName,
            int pageNumber,
            byte[] pagePng,
            List<OcrRow> ocrRows,
            DocumentFamily documentFamily,
            String documentFamilyCode,
            String documentFamilyName,
            PageKind pageKind,
            String pageKindCode,
            String pageKindDisplayName,
            boolean manualPageKind,
            List<SlotValue> slotValues,
            List<WeaveRow> pageWeaveRows) {
        this.streamName = streamName;
        this.pageNumber = pageNumber;
        this.pagePng = pagePng == null ? new byte[0] : pagePng;
        this.ocrRows = ocrRows == null
                ? Collections.<OcrRow>emptyList()
                : Collections.unmodifiableList(new ArrayList<OcrRow>(ocrRows));
        this.documentFamily = documentFamily;
        this.documentFamilyCode = documentFamilyCode == null ? "" : documentFamilyCode;
        this.documentFamilyName = documentFamilyName == null ? "" : documentFamilyName;
        this.pageKind = pageKind == null ? PageKind.UNKNOWN : pageKind;
        this.pageKindCode = pageKindCode == null ? PageKind.UNKNOWN.getCode() : pageKindCode;
        this.pageKindDisplayName = pageKindDisplayName == null ? "未识别" : pageKindDisplayName;
        this.manualPageKind = manualPageKind;
        this.slotValues = slotValues == null
                ? Collections.<SlotValue>emptyList()
                : Collections.unmodifiableList(new ArrayList<SlotValue>(slotValues));
        this.pageWeaveRows = pageWeaveRows == null
                ? Collections.<WeaveRow>emptyList()
                : Collections.unmodifiableList(new ArrayList<WeaveRow>(pageWeaveRows));
    }

    public String getStreamName() {
        return streamName;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public byte[] getPagePng() {
        return pagePng;
    }

    public List<OcrRow> getOcrRows() {
        return ocrRows;
    }

    public DocumentFamily getDocumentFamily() {
        return documentFamily;
    }

    public String getDocumentFamilyCode() {
        return documentFamilyCode;
    }

    public String getDocumentFamilyName() {
        return documentFamilyName;
    }

    public PageKind getPageKind() {
        return pageKind;
    }

    public String getPageKindCode() {
        return pageKindCode;
    }

    public String getPageKindDisplayName() {
        return pageKindDisplayName;
    }

    public boolean isManualPageKind() {
        return manualPageKind;
    }

    public WeavePageResult withOcrRows(List<OcrRow> newOcrRows) {
        return new WeavePageResult(
                streamName,
                pageNumber,
                pagePng,
                newOcrRows,
                documentFamily,
                documentFamilyCode,
                documentFamilyName,
                pageKind,
                pageKindCode,
                pageKindDisplayName,
                manualPageKind,
                slotValues,
                pageWeaveRows);
    }

    /** 手动指定页面类型 (逐页确认模式). */
    public WeavePageResult withPageKind(String kindCode, String displayName) {
        return new WeavePageResult(
                streamName, pageNumber, pagePng, ocrRows,
                documentFamily, documentFamilyCode, documentFamilyName,
                PageKind.fromCode(kindCode), kindCode, displayName,
                true, slotValues, pageWeaveRows);
    }

    public WeavePageResult withReextract(
            PageExtractResult extracted,
            PageExtractService extractService,
            boolean manual) {
        String kindCode = extracted.getPageKindCode();
        return new WeavePageResult(
                streamName,
                pageNumber,
                pagePng,
                ocrRows,
                extractService.getCatalog().getDocumentFamily(),
                extractService.getDocumentFamilyCode(),
                extractService.getDocumentFamilyDisplayName(),
                PageKind.fromCode(kindCode),
                kindCode,
                extractService.pageKindDisplayName(kindCode),
                manual,
                extracted.getSlotValues(),
                extracted.getWeaveRows());
    }

    public List<SlotValue> getSlotValues() {
        return slotValues;
    }

    public List<WeaveRow> getPageWeaveRows() {
        return pageWeaveRows;
    }

    public boolean hasSlotValues() {
        return !slotValues.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WeavePageResult)) {
            return false;
        }
        WeavePageResult that = (WeavePageResult) o;
        return pageNumber == that.pageNumber && Objects.equals(streamName, that.streamName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamName, pageNumber);
    }
}
