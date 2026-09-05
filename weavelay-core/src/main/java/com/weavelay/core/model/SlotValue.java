package com.weavelay.core.model;

/**
 * 页内槽位提取结果 (标签 + 值 + 可选坐标).
 */
public final class SlotValue {

    private final String slotCode;
    private final String slotLabel;
    private final String value;
    private final OcrRow labelRow;
    private final OcrRow valueRow;

    public SlotValue(String slotCode, String slotLabel, String value) {
        this(slotCode, slotLabel, value, null, null);
    }

    public SlotValue(String slotCode, String slotLabel, String value, OcrRow labelRow, OcrRow valueRow) {
        this.slotCode = slotCode;
        this.slotLabel = slotLabel;
        this.value = value == null ? "" : value;
        this.labelRow = labelRow;
        this.valueRow = valueRow;
    }

    public String getSlotCode() {
        return slotCode;
    }

    public String getSlotLabel() {
        return slotLabel;
    }

    public String getValue() {
        return value;
    }

    public OcrRow getLabelRow() {
        return labelRow;
    }

    public OcrRow getValueRow() {
        return valueRow;
    }

    public boolean hasHighlight() {
        return (labelRow != null && labelRow.hasBbox()) || (valueRow != null && valueRow.hasBbox());
    }
}
