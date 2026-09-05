package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;

/**
 * 表格列数据格区域 (无 OCR 文本时仍有几何占位).
 */
public final class TableColumnCellRegion {

    static final String META = "tbl_cell";

    private TableColumnCellRegion() {
    }

    public static OcrRow create(
            String streamName,
            double left,
            double top,
            double right,
            double bottom) {
        return new OcrRow(
                streamName == null ? "" : streamName,
                "",
                0.0,
                left,
                top,
                right,
                bottom,
                META);
    }

    public static boolean isCellRegion(OcrRow row) {
        return row != null && META.equals(row.getMetas());
    }
}
