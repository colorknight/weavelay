package com.weavelay.app.ui;

/** 用户在 PDF 预览上框选的矩形区域 (图像像素坐标). */
public final class RectRegion {
    private final double x, y, width, height;

    public RectRegion(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public double getEndX() { return x + width; }
    public double getEndY() { return y + height; }
}
