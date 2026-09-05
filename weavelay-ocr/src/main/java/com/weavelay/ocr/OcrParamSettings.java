package com.weavelay.ocr;

/**
 * RapidOCR 可调项; 全局生效, 改后需重新运行 OCR.
 *
 * <p>与旧 RapidOcrOnnx ParamConfig 同名同义：
 * {@code boxScoreThresh}=框分数阈值，{@code boxThresh}=DB 概率图二值阈值，
 * {@code padding}=识别前白边（坐标会减回）。
 */
public final class OcrParamSettings {

    public static final int DEFAULT_PADDING = 50;
    public static final int DEFAULT_MAX_SIDE_LEN = 0;
    public static final float DEFAULT_BOX_SCORE_THRESH = 0.5f;
    public static final float DEFAULT_BOX_THRESH = 0.3f;
    public static final float DEFAULT_UNCLIP_RATIO = 1.6f;
    public static final float DEFAULT_MIN_BOX_SCORE = 0f;

    private int padding;
    private int maxSideLen;
    private float boxScoreThresh;
    private float boxThresh;
    private float unClipRatio;
    private float minBoxScore;
    private boolean returnWordBox = true;
    private boolean returnWordLevel;

    public OcrParamSettings() {
        this(DEFAULT_PADDING, DEFAULT_MAX_SIDE_LEN, DEFAULT_BOX_SCORE_THRESH,
                DEFAULT_BOX_THRESH, DEFAULT_UNCLIP_RATIO, DEFAULT_MIN_BOX_SCORE);
    }

    public OcrParamSettings(
            int padding,
            int maxSideLen,
            float boxScoreThresh,
            float boxThresh,
            float unClipRatio,
            float minBoxScore) {
        this.padding = padding;
        this.maxSideLen = maxSideLen;
        this.boxScoreThresh = boxScoreThresh;
        this.boxThresh = boxThresh;
        this.unClipRatio = unClipRatio;
        this.minBoxScore = minBoxScore;
    }

    public static OcrParamSettings defaults() {
        return new OcrParamSettings();
    }

    public OcrParamSettings copy() {
        OcrParamSettings copy = new OcrParamSettings(
                padding, maxSideLen, boxScoreThresh, boxThresh, unClipRatio, minBoxScore);
        copy.returnWordBox = returnWordBox;
        copy.returnWordLevel = returnWordLevel;
        return copy;
    }

    public int getPadding() {
        return padding;
    }

    public void setPadding(int padding) {
        this.padding = padding;
    }

    public int getMaxSideLen() {
        return maxSideLen;
    }

    public void setMaxSideLen(int maxSideLen) {
        this.maxSideLen = maxSideLen;
    }

    public float getBoxScoreThresh() {
        return boxScoreThresh;
    }

    public void setBoxScoreThresh(float boxScoreThresh) {
        this.boxScoreThresh = boxScoreThresh;
    }

    public float getBoxThresh() {
        return boxThresh;
    }

    public void setBoxThresh(float boxThresh) {
        this.boxThresh = boxThresh;
    }

    public float getUnClipRatio() {
        return unClipRatio;
    }

    public void setUnClipRatio(float unClipRatio) {
        this.unClipRatio = unClipRatio;
    }

    public float getMinBoxScore() {
        return minBoxScore;
    }

    public void setMinBoxScore(float minBoxScore) {
        this.minBoxScore = minBoxScore;
    }

    public boolean isReturnWordBox() {
        return returnWordBox;
    }

    public void setReturnWordBox(boolean returnWordBox) {
        this.returnWordBox = returnWordBox;
    }

    public boolean isReturnWordLevel() {
        return returnWordLevel;
    }

    public void setReturnWordLevel(boolean returnWordLevel) {
        this.returnWordLevel = returnWordLevel;
    }
}
