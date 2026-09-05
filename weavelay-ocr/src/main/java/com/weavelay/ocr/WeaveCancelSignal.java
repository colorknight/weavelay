package com.weavelay.ocr;

/**
 * 织版/OCR 批处理的可协作取消信号.
 */
public final class WeaveCancelSignal {

    private volatile boolean cancelled;

    public void reset() {
        cancelled = false;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    public void check() {
        if (isCancelled()) {
            throw new WeaveCancelledException();
        }
    }
}
