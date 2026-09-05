package com.weavelay.ocr;

/**
 * PDF 按页 OCR 进度回调。
 */
@FunctionalInterface
public interface WeaveProgressListener {

    void onProgress(int currentPage, int totalPages, String message);
}
