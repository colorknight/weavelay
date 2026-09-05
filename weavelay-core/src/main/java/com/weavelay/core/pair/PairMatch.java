package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;

public final class PairMatch {

    private final OcrRow labelRow;
    private final OcrRow valueRow;
    private final String value;

    private PairMatch(OcrRow labelRow, OcrRow valueRow, String value) {
        this.labelRow = labelRow;
        this.valueRow = valueRow;
        this.value = value == null ? "" : value;
    }

    public static PairMatch empty() {
        return new PairMatch(null, null, "");
    }

    public static PairMatch of(OcrRow labelRow, OcrRow valueRow, String value) {
        return new PairMatch(labelRow, valueRow, value);
    }

    public OcrRow getLabelRow() {
        return labelRow;
    }

    public OcrRow getValueRow() {
        return valueRow;
    }

    public String getValue() {
        return value;
    }
}
