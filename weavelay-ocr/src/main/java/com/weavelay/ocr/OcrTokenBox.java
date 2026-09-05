package com.weavelay.ocr;

/**
 * OCR 行内 token（字符或词）的包围盒与置信度。
 */
public final class OcrTokenBox {

    private final String text;
    private final double confidence;
    private double startX;
    private double startY;
    private double endX;
    private double endY;

    public OcrTokenBox(
            String text,
            double confidence,
            double startX,
            double startY,
            double endX,
            double endY) {
        this.text = text == null ? "" : text;
        this.confidence = confidence;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
    }

    public String getText() {
        return text;
    }

    public double getConfidence() {
        return confidence;
    }

    public double getStartX() {
        return startX;
    }

    public double getStartY() {
        return startY;
    }

    public double getEndX() {
        return endX;
    }

    public double getEndY() {
        return endY;
    }

    void scale(double scaleX, double scaleY) {
        startX *= scaleX;
        startY *= scaleY;
        endX *= scaleX;
        endY *= scaleY;
    }

    void shift(double dx, double dy) {
        startX += dx;
        startY += dy;
        endX += dx;
        endY += dy;
    }
}
