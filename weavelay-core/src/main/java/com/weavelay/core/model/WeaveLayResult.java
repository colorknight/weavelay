package com.weavelay.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次织版会话: 各页 OCR + 合并后的行.
 */
public final class WeaveLayResult {

    private final List<WeavePageResult> pages;
    private final List<WeaveRow> weaveRows;

    public WeaveLayResult(List<WeavePageResult> pages, List<WeaveRow> weaveRows) {
        this.pages = pages == null
                ? Collections.<WeavePageResult>emptyList()
                : Collections.unmodifiableList(new ArrayList<WeavePageResult>(pages));
        this.weaveRows = weaveRows == null
                ? Collections.<WeaveRow>emptyList()
                : Collections.unmodifiableList(new ArrayList<WeaveRow>(weaveRows));
    }

    public List<WeavePageResult> getPages() {
        return pages;
    }

    public List<WeaveRow> getWeaveRows() {
        return weaveRows;
    }
}
