package com.weavelay.core.display;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.page.PageKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCatalogLineComposerTest {

    private static final List<SlotValue> TBL_SLOTS = List.of(
            new SlotValue("tbl_supply_status", "供应状态", "", null, null),
            new SlotValue("tbl_blank_type", "毛坯种类", "", null, null),
            new SlotValue("tbl_blank_size", "毛坯尺寸", "", null, null),
            new SlotValue("tbl_blank_weight", "毛坯重量kg", "", null, null));

    @Test
    void insertsEmptyDataLineUnderEachTableHeader() {
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯种类", 200, 200, 280, 220),
                row("毛坯重量kg", 300, 200, 400, 220));

        List<SlotValue> lines = ProcessCatalogLineComposer.composePageLines(rows, TBL_SLOTS);

        assertEquals(6, lines.size());
        assertEquals("#1", lines.get(0).getSlotLabel());
        assertEquals("供应状态", lines.get(0).getValue());
        assertEquals("#2", lines.get(1).getSlotLabel());
        assertEquals("↳ 供应状态  —", lines.get(1).getValue());
        assertEquals("#6", lines.get(5).getSlotLabel());
        assertEquals("↳ 毛坯重量kg  —", lines.get(5).getValue());
    }

    @Test
    void interleavesWhenTableHeadersPresentEvenIfPageKindUnknown() {
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯种类", 200, 200, 280, 220),
                row("毛坯尺寸", 300, 200, 380, 220));

        List<SlotValue> lines = ProcessCatalogLineComposer.composePageLines(
                rows, TBL_SLOTS, PageKind.UNKNOWN.getCode());

        assertEquals(6, lines.size());
        assertEquals("#2", lines.get(1).getSlotLabel());
        assertEquals("↳ 供应状态  —", lines.get(1).getValue());
    }

    @Test
    void skipsInterleaveWhenSingleRow() {
        List<OcrRow> rows = List.of(row("供应状态", 100, 200, 180, 220));
        List<SlotValue> lines = ProcessCatalogLineComposer.composePageLines(rows, TBL_SLOTS);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).getSlotLabel().startsWith("#"));
    }

    @Test
    void skipsInterleaveWhenNoTblSlots() {
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯种类", 200, 200, 280, 220),
                row("毛坯重量kg", 300, 200, 400, 220));

        List<SlotValue> lines = ProcessCatalogLineComposer.composePageLines(rows, List.of());

        assertEquals(3, lines.size());
        assertEquals("供应状态", lines.get(0).getValue());
        assertEquals("毛坯种类", lines.get(1).getValue());
        assertEquals("毛坯重量kg", lines.get(2).getValue());
    }

    @Test
    void emptyTblSlotsEmptyLabelsNoInterleave() {
        Set<String> emptyLabels = Set.of();
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯种类", 200, 200, 280, 220),
                row("毛坯重量kg", 300, 200, 400, 220));

        assertFalse(ProcessCatalogLineComposer.shouldInterleaveTableColumns(rows, emptyLabels));
    }

    private static OcrRow row(String text, double x1, double y1, double x2, double y2) {
        return new OcrRow("p1", text, 1.0, x1, y1, x2, y2, null);
    }
}
