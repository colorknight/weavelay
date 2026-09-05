package com.weavelay.ocr.layout;

import java.util.Objects;

/**
 * PP-DocLayoutV3 单框检测结果，坐标为原图像素空间.
 */
public final class PpDocLayoutBox {

    private final int labelIndex;
    private final String labelName;
    private final float score;
    private final float xmin;
    private final float ymin;
    private final float xmax;
    private final float ymax;
    private final float readOrder;

    public PpDocLayoutBox(
            int labelIndex,
            String labelName,
            float score,
            float xmin,
            float ymin,
            float xmax,
            float ymax,
            float readOrder) {
        this.labelIndex = labelIndex;
        this.labelName = Objects.requireNonNull(labelName, "labelName");
        this.score = score;
        this.xmin = xmin;
        this.ymin = ymin;
        this.xmax = xmax;
        this.ymax = ymax;
        this.readOrder = readOrder;
    }

    public int getLabelIndex() { return labelIndex; }

    public String getLabelName() { return labelName; }

    public float getScore() { return score; }

    public float getXmin() { return xmin; }

    public float getYmin() { return ymin; }

    public float getXmax() { return xmax; }

    public float getYmax() { return ymax; }

    public float getReadOrder() { return readOrder; }

    /** 宽度（像素）. */
    public float getWidth() { return xmax - xmin; }

    /** 高度（像素）. */
    public float getHeight() { return ymax - ymin; }

    /** 是否为公式类标签 (display_formula / inline_formula). */
    public boolean isFormula() {
        return "display_formula".equals(labelName) || "inline_formula".equals(labelName);
    }

}
