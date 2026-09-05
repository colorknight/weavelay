package com.weavelay.ocr.pdf;

import com.weavelay.core.model.OcrRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 PDF 直接提取区域内文字 (无需 OCR).
 * 坐标: 输入为图像像素坐标 (300 DPI, 原点左上), 内部翻转为 PDF 点坐标 (原点左下).
 */
public final class PdfTextExtractor {

    private static final float DPI = 300f;
    private static final float PX_TO_PT = 72f / DPI;

    private PdfTextExtractor() {
    }

    public static String extractText(Path pdfPath, int pageIndex,
                                      double x, double y, double w, double h) throws IOException {
        List<OcrRow> rows = extractOcrRows(pdfPath, pageIndex, x, y, w, h);
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OcrRow row : rows) {
            if (row == null || row.getFeature() == null || row.getFeature().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(row.getFeature().trim());
        }
        return sb.toString().trim();
    }

    /**
     * 提取区域内带 bbox 的文字行；坐标已换算为<strong>相对区域左上角</strong>的像素坐标，
     * 可与区域裁切后的 OCR 结果直接合并。
     */
    public static List<OcrRow> extractOcrRows(
            Path pdfPath,
            int pageIndex,
            double regionX,
            double regionY,
            double regionW,
            double regionH) throws IOException {
        if (pdfPath == null || regionW <= 0 || regionH <= 0) {
            return List.of();
        }
        List<OcrRow> rows = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                return List.of();
            }
            PDPage page = doc.getPage(pageIndex);
            float pageHPt = page.getMediaBox().getHeight();

            float left = (float) (regionX * PX_TO_PT);
            float right = (float) ((regionX + regionW) * PX_TO_PT);
            float pdfBottom = pageHPt - (float) ((regionY + regionH) * PX_TO_PT);
            float pdfTop = pageHPt - (float) (regionY * PX_TO_PT);

            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text == null || text.isBlank() || positions == null || positions.isEmpty()) {
                        return;
                    }
                    float minX = Float.POSITIVE_INFINITY;
                    float maxX = Float.NEGATIVE_INFINITY;
                    float minY = Float.POSITIVE_INFINITY;
                    float maxY = Float.NEGATIVE_INFINITY;
                    for (TextPosition tp : positions) {
                        if (tp == null) {
                            continue;
                        }
                        float x0 = tp.getXDirAdj();
                        float y0 = tp.getYDirAdj();
                        float x1 = x0 + Math.max(tp.getWidthDirAdj(), 0.1f);
                        float y1 = y0 + Math.max(tp.getHeightDir(), tp.getFontSizeInPt() * 0.8f);
                        minX = Math.min(minX, x0);
                        maxX = Math.max(maxX, x1);
                        minY = Math.min(minY, y0);
                        maxY = Math.max(maxY, y1);
                    }
                    if (!Float.isFinite(minX)) {
                        return;
                    }
                    float cx = (minX + maxX) / 2f;
                    float cy = (minY + maxY) / 2f;
                    if (cx < left || cx > right || cy < pdfBottom || cy > pdfTop) {
                        return;
                    }
                    // PDF Y 自下而上 → 图像 Y 自上而下；再减区域原点
                    double imgX0 = minX / PX_TO_PT - regionX;
                    double imgX1 = maxX / PX_TO_PT - regionX;
                    double imgY0 = (pageHPt - maxY) / PX_TO_PT - regionY;
                    double imgY1 = (pageHPt - minY) / PX_TO_PT - regionY;
                    String cleaned = text.trim();
                    if (cleaned.isEmpty()) {
                        return;
                    }
                    rows.add(new OcrRow(
                            "pdf-text",
                            cleaned,
                            1.0,
                            imgX0,
                            imgY0,
                            imgX1,
                            imgY1,
                            null));
                }
            };
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(doc);
        }
        return rows;
    }
}
