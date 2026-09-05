package com.weavelay.core.formula;

/**
 * 单张公式图 → LaTeX（进程内 ONNX）。
 */
@FunctionalInterface
public interface FormulaImageRecognizer {

    String recognizeImage(byte[] imagePng) throws Exception;
}
