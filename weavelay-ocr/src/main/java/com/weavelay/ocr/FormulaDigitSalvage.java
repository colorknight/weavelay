package com.weavelay.ocr;

import com.weavelay.core.formula.DiameterLatexNormalizer;
import com.weavelay.core.model.OcrRow;

import java.util.List;

/**
 * 公式模型整式无数字时（如 {@code \\beta\\mathrm{\\ln}}），用同一裁图 OCR 抽阿拉伯数字补 ⌀。
 */
public final class FormulaDigitSalvage {

    private FormulaDigitSalvage() {}

    public static String salvageIfNoDigit(String latex, byte[] formulaCrop, RapidOcrService ocr) {
        if (latex != null && !DiameterLatexNormalizer.needsDigitSalvage(latex)) {
            return latex;
        }
        if (formulaCrop == null || formulaCrop.length == 0 || ocr == null) {
            return latex == null ? "" : latex;
        }
        try {
            RapidOcrService.prepareNativeRuntime();
            List<OcrRow> rows = ocr.recognizeImageBytes(formulaCrop, "formula-digit");
            return salvageFromRows(latex, rows);
        } catch (Exception ignored) {
        }
        return latex == null ? "" : latex;
    }

    /** 已解码裁图直传 OCR，免 PNG 往返。 */
    public static String salvageIfNoDigit(
            String latex, java.awt.image.BufferedImage formulaCrop, RapidOcrService ocr) {
        if (latex != null && !DiameterLatexNormalizer.needsDigitSalvage(latex)) {
            return latex;
        }
        if (formulaCrop == null || ocr == null) {
            return latex == null ? "" : latex;
        }
        try {
            RapidOcrService.prepareNativeRuntime();
            List<OcrRow> rows = ocr.recognizeBufferedImage(formulaCrop, "formula-digit");
            return salvageFromRows(latex, rows);
        } catch (Exception ignored) {
        }
        return latex == null ? "" : latex;
    }

    private static String salvageFromRows(String latex, List<OcrRow> rows) {
        String digits = digitsOnly(rows);
        if (digits != null && digits.matches("\\d{1,4}")) {
            StringBuilder spaced = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0) {
                    spaced.append(' ');
                }
                spaced.append(digits.charAt(i));
            }
            return DiameterLatexNormalizer.normalize("\\varnothing " + spaced);
        }
        return latex == null ? "" : latex;
    }

    private static String digitsOnly(List<OcrRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (OcrRow row : rows) {
            if (row == null || row.getFeature() == null) {
                continue;
            }
            for (int i = 0; i < row.getFeature().length(); i++) {
                char c = row.getFeature().charAt(i);
                if (c >= '0' && c <= '9') {
                    sb.append(c);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
