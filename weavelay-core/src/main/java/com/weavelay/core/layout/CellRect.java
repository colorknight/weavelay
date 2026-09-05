package com.weavelay.core.layout;

/**
 * 表格 cell，Excel row/col/rowspan/colspan 模型。
 * 坐标与 OCR 结果 (OcrRow bbox) 在同一空间 (图像像素)。
 */
public final class CellRect {

    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final int row;
    private final int col;
    private final int rowspan;
    private final int colspan;
    private final String text;

    public CellRect(double x, double y, double width, double height, int row, int col, String text) {
        this(x, y, width, height, row, col, 1, 1, text);
    }

    public CellRect(double x, double y, double width, double height,
                    int row, int col, int rowspan, int colspan, String text) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.row = row;
        this.col = col;
        this.rowspan = rowspan;
        this.colspan = colspan;
        this.text = text;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public double getEndX() { return x + width; }
    public double getEndY() { return y + height; }
    public int getRow() { return row; }
    public int getCol() { return col; }
    public int getRowspan() { return rowspan; }
    public int getColspan() { return colspan; }
    public String getText() { return text; }

    public double centerX() { return x + width / 2.0; }
    public double centerY() { return y + height / 2.0; }

    @Override
    public String toString() {
        return String.format("Cell[r=%d,c=%d,rs=%d,cs=%d,xy=(%.0f,%.0f),wh=(%.0f,%.0f)]",
                row, col, rowspan, colspan, x, y, width, height);
    }
}
