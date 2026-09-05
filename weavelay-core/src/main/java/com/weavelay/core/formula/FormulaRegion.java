package com.weavelay.core.formula;

/**
 * 公式识别区域: 页面 PNG 中的一个矩形区域及其对应的槽位标识.
 */
public final class FormulaRegion {

    private final String slotCode;
    private final int x;
    private final int y;
    private final int w;
    private final int h;

    public FormulaRegion(String slotCode, int x, int y, int w, int h) {
        this.slotCode = slotCode;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public String getSlotCode() {
        return slotCode;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getW() {
        return w;
    }

    public int getH() {
        return h;
    }
}
