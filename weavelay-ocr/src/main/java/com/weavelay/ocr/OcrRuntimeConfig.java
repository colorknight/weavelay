package com.weavelay.ocr;

import java.nio.file.Path;

/**
 * OCR 配置: PP-OCRv6 ONNX 模型.
 */
public final class OcrRuntimeConfig {

    private Path v6ModelDir;

    public OcrRuntimeConfig() {
        this.v6ModelDir = PpOcrV5ModelPaths.defaultModelDir();
    }

    public OcrRuntimeConfig(Path v6ModelDir) {
        this.v6ModelDir = v6ModelDir == null ? PpOcrV5ModelPaths.defaultModelDir() : v6ModelDir;
    }

    public OcrRuntimeConfig copy() {
        return new OcrRuntimeConfig(v6ModelDir);
    }

    public Path getV6ModelDir() {
        return v6ModelDir;
    }

    public void setV6ModelDir(Path v6ModelDir) {
        this.v6ModelDir = v6ModelDir == null ? PpOcrV5ModelPaths.defaultModelDir() : v6ModelDir;
    }

    public Path resolveV6ModelDir() {
        Path configured = v6ModelDir == null ? PpOcrV5ModelPaths.defaultModelDir() : v6ModelDir;
        if (PpOcrV5ModelPaths.isReady(configured) && PpOcrV5ModelPaths.isAsciiPath(configured)) {
            return configured;
        }
        Path project = PpOcrV5ModelPaths.defaultModelDir();
        if (PpOcrV5ModelPaths.isReady(project)) {
            return project;
        }
        return configured;
    }
}
