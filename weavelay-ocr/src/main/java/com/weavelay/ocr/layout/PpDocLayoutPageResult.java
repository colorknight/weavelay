package com.weavelay.ocr.layout;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 单张图片的版式检测结果. */
public final class PpDocLayoutPageResult {

    private final int sourceWidth;
    private final int sourceHeight;
    private final List<PpDocLayoutBox> boxes;

    public PpDocLayoutPageResult(int sourceWidth, int sourceHeight, List<PpDocLayoutBox> boxes) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.boxes = Collections.unmodifiableList(Objects.requireNonNull(boxes, "boxes"));
    }

    public int getSourceWidth() { return sourceWidth; }

    public int getSourceHeight() { return sourceHeight; }

    public List<PpDocLayoutBox> getBoxes() { return boxes; }

    /** 筛选公式类布局块 (display_formula / inline_formula), 按阅读顺序排列. */
    public List<PpDocLayoutBox> getFormulaBoxes() {
        return boxes.stream()
                .filter(PpDocLayoutBox::isFormula)
                .toList();
    }
}
