package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.WeavePageResult;

/**
 * 单页 OCR 完成回调 (用于 UI 逐页刷新).
 */
@FunctionalInterface
public interface WeavePageListener {

    void onPageCompleted(WeavePageResult page, int pageIndex, int totalPages);
}
