package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;

import java.util.List;

/**
 * 单页型解析器.
 */
public interface PageExtractor {

    PageKind supportedKind();

    PageExtractResult extract(
            String streamName,
            List<OcrRow> pageRows,
            PageKindDefinition definition);
}
