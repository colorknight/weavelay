package com.weavelay.core.extract;

import com.weavelay.core.merge.RowMerger;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeaveRow;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;

import java.util.Collections;
import java.util.List;

/**
 * 未配置专用解析器时: 按页型 merge 规则输出织版行.
 */
public final class MergeFallbackExtractor implements PageExtractor {

    @Override
    public PageKind supportedKind() {
        return PageKind.UNKNOWN;
    }

    @Override
    public PageExtractResult extract(
            String streamName,
            List<OcrRow> pageRows,
            PageKindDefinition definition) {
        PageKind kind = definition == null ? PageKind.UNKNOWN : definition.getKind();
        List<WeaveRow> weaveRows;
        if (definition != null && definition.getRule() != null) {
            weaveRows = RowMerger.merge(pageRows, definition.getRule().toMergeParams());
        } else {
            weaveRows = RowMerger.merge(pageRows, new com.weavelay.core.model.MergeParams());
        }
        return new PageExtractResult(kind, Collections.<SlotValue>emptyList(), weaveRows);
    }
}
