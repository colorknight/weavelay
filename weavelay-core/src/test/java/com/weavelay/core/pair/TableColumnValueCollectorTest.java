package com.weavelay.core.pair;

import com.weavelay.core.model.OcrRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableColumnValueCollectorTest {

    @Test
    void emitsEmptyValuesWhenDataRowHasNoOcr() {
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯种类", 200, 200, 280, 220),
                row("毛坯尺寸", 300, 200, 380, 220));

        Map<String, PairMatch> matches = TableColumnValueCollector.collectColumns(
                rows,
                List.of("供应状态", "毛坯种类", "毛坯尺寸"),
                80);

        assertEquals("", matches.get("供应状态").getValue());
        assertEquals("", matches.get("毛坯种类").getValue());
        assertEquals("", matches.get("毛坯尺寸").getValue());
        assertTrue(TableColumnCellRegion.isCellRegion(matches.get("供应状态").getValueRow()));
        assertTrue(matches.get("供应状态").getLabelRow() != null);
    }

    @Test
    void readsValueBelowMatchingHeader() {
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯种类", 200, 200, 280, 220),
                row("热轧", 110, 250, 160, 270));

        Map<String, PairMatch> matches = TableColumnValueCollector.collectColumns(
                rows,
                List.of("供应状态", "毛坯种类"),
                80);

        assertEquals("热轧", matches.get("供应状态").getValue());
        assertEquals("", matches.get("毛坯种类").getValue());
    }

    private static OcrRow row(String text, double x1, double y1, double x2, double y2) {
        return new OcrRow("p1", text, 1.0, x1, y1, x2, y2, null);
    }
}
