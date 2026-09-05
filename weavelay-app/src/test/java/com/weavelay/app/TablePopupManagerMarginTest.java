package com.weavelay.app;

import com.weavelay.core.layout.CellRect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablePopupManagerMarginTest {

    @Test
    void refineForcesLeftMarginWhenExtraMostlyEmptyColumn() {
        // 8 物理列、7 表头；最左列几乎空（仅一行噪点）→ 应判左边距
        List<CellRect> cells = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 8; c++) {
                String text = "";
                if (c == 0 && r == 0) {
                    text = "."; // 噪点，仍应 ≥80% 空
                } else if (c == 1) {
                    text = String.valueOf(r + 1);
                } else if (c == 3) {
                    text = "工序" + r;
                }
                cells.add(new CellRect(c * 10, r * 10, 10, 10, r, c, text));
            }
        }
        int[] base = TablePopupManager.resolveMarginOffsets(cells, null);
        int[] refined = TablePopupManager.refineMarginOffsets(cells, base, 7);
        assertArrayEquals(new int[] {refined[0], 1}, new int[] {refined[0], refined[1]});
        assertTrue(refined[1] == 1, "left margin should be forced");
    }

    @Test
    void seemsColumnShiftedDetectsProcessNameInEquipmentCol() {
        String struct = """
                |工序号|特性代号|工序名称|设备.名称|设备.型号|页码|注|
                |---|---|---|---|---|---|---|
                |1||来料图|||2||
                |2||车一端内形及外径|普通车床|CW3333|3||
                """;
        String edited = """
                |工序号|特性代号|工序名称|设备.名称|设备.型号|页码|注|
                |---|---|---|---|---|---|---|
                |1|||来料图||2||
                |2|||车一端内形及外径|普通车床 CW3333|3||
                """;
        assertTrue(TablePopupManager.seemsColumnShifted(struct, edited));
        assertFalse(TablePopupManager.seemsColumnShifted(struct, struct));
    }
}
