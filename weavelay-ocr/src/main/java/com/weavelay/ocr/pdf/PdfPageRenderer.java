package com.weavelay.ocr.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * PDF 逐页渲染为 PNG 字节，与 yansee {@code PdfPngRenderer} 相同语义：按页顺序、默认全页。
 */
public final class PdfPageRenderer {

    public static final int DEFAULT_RENDER_DPI = 300;

    private PdfPageRenderer() {
    }

    public static List<byte[]> renderToPngBytes(Path pdfPath) throws IOException {
        return renderToPngBytes(pdfPath, 0, DEFAULT_RENDER_DPI);
    }

    public static List<byte[]> renderToPngBytes(Path pdfPath, int maxPages, int dpi) throws IOException {
        Objects.requireNonNull(pdfPath, "pdfPath");
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return renderToPngBytes(document, maxPages, dpi);
        }
    }

    public static List<byte[]> renderToPngBytes(PDDocument document, int maxPages, int dpi) throws IOException {
        Objects.requireNonNull(document, "document");
        int pageCount = document.getNumberOfPages();
        if (pageCount <= 0) {
            return Collections.emptyList();
        }
        int limit = maxPages <= 0 ? pageCount : Math.min(pageCount, maxPages);
        PDFRenderer renderer = new PDFRenderer(document);
        List<byte[]> pages = new ArrayList<>(limit);
        for (int pageIndex = 0; pageIndex < limit; pageIndex++) {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            pages.add(baos.toByteArray());
        }
        return pages;
    }
}
