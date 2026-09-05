package com.weavelay.app;

import com.weavelay.core.layout.CellRect;
import com.weavelay.core.model.OcrRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablePopupManagerPairingTest {

    @Test
    void skipSparseCodeHeaderShiftsFromProcessName() {
        String[] headers = {"工序号", "特性代号", "工序名称", "设备.名称", "页码"};
        assertEquals(0, TablePopupManager.skipSparseCodeHeaderColumn(0, headers, 4));
        assertEquals(2, TablePopupManager.skipSparseCodeHeaderColumn(1, headers, 4));
        assertEquals(1, TablePopupManager.skipSparseCodeHeaderColumn(1, headers, 5));
    }

    @Test
    void processNoDoesNotLandInFeatureCodeWhenPaperHasNoSuchColumn() {
        String label = "*工序目录表:工序号,特性代号,工序名称,设备.名称,设备.型号,页码,注";
        List<CellRect> cells = new ArrayList<>();
        double[] x = {0, 40, 160, 240, 320, 380};
        double[] w = {40, 120, 80, 80, 60, 40};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 6; c++) {
                cells.add(new CellRect(x[c], r * 24, w[c], 24, r, c, ""));
            }
        }
        List<OcrRow> ocr = List.of(
                new OcrRow("s", "1Y", 1, 6, 28, 32, 44, null),
                new OcrRow("s", "检验", 1, 50, 28, 120, 44, null),
                new OcrRow("s", "工作台", 1, 170, 28, 230, 44, null));
        String md = TablePopupManager.buildTableMarkdown(label, cells, ocr);
        assertTrue(md.contains("| 1Y |  | 检验 |"), md);
        assertTrue(!md.contains("|  | 1Y |"), md);
    }

    @Test
    void firstColumnCorrectStillShiftsTheRestWhenProcessNameStuckInCodeCol() {
        String[] headers = {"工序号", "特性代号", "工序名称", "设备.名称", "页码"};
        String[][] grid = {
                headers,
                {"1Y", "检验", "工作台", "9", ""},
                {"2", "车一端", "数控车床", "5", ""},
                {"3", "去毛刺", "", "7", ""},
        };
        TablePopupManager.realignSparseCodeColumn(grid, 4, 5, headers, new ArrayList<>());
        assertEquals("1Y", grid[1][0]);
        assertEquals("", grid[1][1]);
        assertEquals("检验", grid[1][2]);
        assertEquals("工作台", grid[1][3]);
        assertEquals("2", grid[2][0]);
        assertEquals("车一端", grid[2][2]);
    }

    @Test
    void wholeTableShiftsLeftWhenStepNoSitsInCodeColumn() {
        String[] headers = {"工序号", "特性代号", "工序名称", "设备.名称", "页码"};
        String[][] grid = {
                headers,
                {"", "1Y", "检验", "工作台", "9"},
                {"", "2", "车一端", "数控车床", "5"},
                {"", "3", "去毛刺", "", "7"},
        };
        TablePopupManager.realignSparseCodeColumn(grid, 4, 5, headers, new ArrayList<>());
        assertEquals("1Y", grid[1][0]);
        assertEquals("", grid[1][1]);
        assertEquals("检验", grid[1][2]);
        assertEquals("工作台", grid[1][3]);
    }

    @Test
    void shiftAllMovesContentLeft() {
        var data = javafx.collections.FXCollections.<javafx.collections.ObservableList<String>>observableArrayList();
        data.add(javafx.collections.FXCollections.observableArrayList("", "1Y", "检验"));
        data.add(javafx.collections.FXCollections.observableArrayList("", "2", "车"));
        TablePopupManager.shiftAll(data, 3, -1, 0);
        assertEquals("1Y", data.get(0).get(0));
        assertEquals("检验", data.get(0).get(1));
        assertEquals("", data.get(0).get(2));
        assertEquals("2", data.get(1).get(0));
    }
}
