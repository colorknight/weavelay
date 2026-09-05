package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeaveRow;
import com.weavelay.core.page.PageKind;

import java.util.Collections;
import java.util.List;

public final class PageExtractResult {

    private final PageKind pageKind;
    private final List<SlotValue> slotValues;
    private final List<WeaveRow> weaveRows;

    public PageExtractResult(PageKind pageKind, List<SlotValue> slotValues, List<WeaveRow> weaveRows) {
        this.pageKind = pageKind == null ? PageKind.UNKNOWN : pageKind;
        this.slotValues = slotValues == null
                ? Collections.<SlotValue>emptyList()
                : Collections.unmodifiableList(slotValues);
        this.weaveRows = weaveRows == null
                ? Collections.<WeaveRow>emptyList()
                : Collections.unmodifiableList(weaveRows);
    }

    public PageKind getPageKind() {
        return pageKind;
    }

    public String getPageKindCode() {
        return pageKind == null ? PageKind.UNKNOWN.getCode() : pageKind.getCode();
    }

    public List<SlotValue> getSlotValues() {
        return slotValues;
    }

    public List<WeaveRow> getWeaveRows() {
        return weaveRows;
    }
}
