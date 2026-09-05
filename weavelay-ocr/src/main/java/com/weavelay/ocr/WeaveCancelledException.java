package com.weavelay.ocr;

/**
 * 用户主动停止织版/OCR 批处理.
 */
public final class WeaveCancelledException extends RuntimeException {

    public WeaveCancelledException() {
        super("已停止");
    }
}
