package com.weavelay.core.merge;

import com.weavelay.core.model.MergeParams;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.WeaveRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowMergerTest {

    @Test
    void sortForReadingOrdersLeftToRightWithinLine() {
        List<OcrRow> rows = Arrays.asList(
                row("p1", "程", 2400, 450, 2570, 690),
                row("p1", "工艺规", 1175, 460, 2200, 680));

        List<OcrRow> ordered = RowMerger.sortForReading(rows);

        assertEquals("工艺规", ordered.get(0).getFeature());
        assertEquals("程", ordered.get(1).getFeature());
    }

    @Test
    void sortForReadingPlacesLeftTallBoxBeforeRightHeaderOnSameBand() {
        List<OcrRow> rows = Arrays.asList(
                row("p1", "产品代号", 1365, 974, 1621, 1051),
                row("p1", "74", 120, 900, 280, 1100));

        List<OcrRow> ordered = RowMerger.sortForReading(rows);

        assertEquals("74", ordered.get(0).getFeature());
        assertEquals("产品代号", ordered.get(1).getFeature());
    }

    @Test
    void mergeCoverSample() {
        List<OcrRow> rows = Arrays.asList(
                row("page0", "工艺规程", 1175, 450, 2570, 690),
                row("page0", "粗加工", 1778, 771, 1964, 871),
                row("page0", "产品代号", 1365, 974, 1621, 1051),
                row("page0", "ABC-1. 2A", 1927, 970, 2102, 1040),
                row("page0", "零部件代号", 1365, 1151, 1617, 1228),
                row("page0", "03-2", 1964, 1147, 2066, 1221),
                row("page0", "飞边", 1971, 1317, 2062, 1398),
                row("page0", "零部件名称", 1376, 1339, 1617, 1406));

        MergeParams params = new MergeParams();
        params.setYThreshold(80);
        params.setXThreshold(350);

        List<WeaveRow> merged = RowMerger.mergeByPage(rows, params);
        assertTrue(merged.size() >= 4);
        assertEquals("产品代号ABC-1. 2A", findFeature(merged, 3));
        assertEquals("零部件名称", findFeature(merged, 5));
        assertEquals("飞边", findFeature(merged, 6));
    }

    @Test
    void sortForReadingOrdersStackedMaterialSpecsTopToBottom() {
        List<OcrRow> rows = Arrays.asList(
                row("p1", "圆钢", 80, 105, 160, 125),
                row("p1", "35CrMnSiA-GB/T3077-2015", 80, 155, 260, 175),
                row("p1", "150-GB/T702-2017", 80, 130, 220, 150));

        List<OcrRow> ordered = RowMerger.sortForReading(rows);

        assertEquals("圆钢", ordered.get(0).getFeature());
        assertEquals("150-GB/T702-2017", ordered.get(1).getFeature());
        assertEquals("35CrMnSiA-GB/T3077-2015", ordered.get(2).getFeature());
    }

    private static OcrRow row(
            String streamName,
            String feature,
            double startX,
            double startY,
            double endX,
            double endY) {
        return new OcrRow(streamName, feature, 0.99, startX, startY, endX, endY, null);
    }

    private static String findFeature(List<WeaveRow> rows, int seq) {
        for (WeaveRow row : rows) {
            if (row.getSeq() == seq) {
                return row.getFeature();
            }
        }
        return "";
    }
}
