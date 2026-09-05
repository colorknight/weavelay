package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrCellValueCollectorTest {

    @Test
    void collectsMultiLineValuesBesideLabel() {
        List<OcrRow> rows = List.of(
                row("材料", 10, 100, 50, 180),
                row("圆钢", 80, 105, 160, 125),
                row("150-GB/T702-2017", 80, 130, 220, 150),
                row("35CrMnSiA-GB/T3077-2015", 80, 155, 260, 175));

        PairMatch match = OcrCellValueCollector.collectBesideLabel(rows, "材料", 80, 300);

        assertEquals("圆钢\n150-GB/T702-2017\n35CrMnSiA-GB/T3077-2015", match.getValue());
        assertEquals("圆钢", match.getValueRow().getFeature());
    }

    @Test
    void keepsStackedSpecTopToBottomWhenOcrOrderIsReversed() {
        List<OcrRow> rows = List.of(
                row("材料", 10, 100, 50, 180),
                row("圆钢", 80, 105, 160, 125),
                row("35CrMnSiA-GB/T3077-2015", 80, 155, 260, 175),
                row("150-GB/T702-2017", 80, 130, 220, 150));

        PairMatch match = OcrCellValueCollector.collectBesideLabel(rows, "材料", 80, 300);

        assertEquals("圆钢\n150-GB/T702-2017\n35CrMnSiA-GB/T3077-2015", match.getValue());
    }

    @Test
    void picksBodyLabelWhenHeaderAlsoHasSameText() {
        List<OcrRow> rows = List.of(
                row("材料", 10, 20, 50, 40),
                row("材料", 10, 100, 50, 180),
                row("圆钢", 80, 105, 160, 125),
                row("150-GB/T702-2017", 80, 130, 220, 150));

        PairMatch match = OcrCellValueCollector.collectBesideLabel(rows, "材料", 80, 300);

        assertTrue(match.getValue().contains("圆钢"));
        assertTrue(match.getValue().contains("150-GB/T702-2017"));
        assertEquals(100, match.getLabelRow().getStartY(), 0.01);
    }

    private static OcrRow row(String text, double x1, double y1, double x2, double y2) {
        return new OcrRow("p1", text, 1.0, x1, y1, x2, y2, null);
    }
}
