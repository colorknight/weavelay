package com.weavelay.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TablePopupManagerMergeTest {

    @Test
    void mergeAlignsCompactEditedRowsOntoStructureWithEmptySpacers() {
        String structure = """
                ### 工序目录表
                |工序号|工序名称|
                |---|---|
                ||来料图|
                |||
                |1|车一端内形及外径|
                |||
                |1Y|检验|
                |||
                |2|车另一端及内形|
                |||
                |2Y|检验|
                |||
                |3|去毛刺|
                |||
                |F1|包装|
                |||
                ||加工后成品图|
                |||
                ||转热处理|
                """;
        // 槽位侧常被压成无空行
        String edited = """
                ### 工序目录表
                |工序号|工序名称|
                |---|---|
                ||来料图|
                |1|车一端内形及外径|
                |1Y|检验|
                |2|车另一端及内形|
                |2Y|检验|
                |3|去毛刺|
                |F1|包装|
                ||加工后成品图|
                ||转热处理|
                """;
        String merged = TablePopupManager.mergeMarkdownPreserveStructure(structure, edited);
        assertTrue(merged.contains("车一端内形及外径"), merged);
        assertTrue(merged.contains("转热处理"), merged);
        // 不得把转热处理盖到工序号 1 上
        assertTrue(!merged.matches("(?s).*\\|\\s*1\\s*\\|\\s*转热处理\\s*\\|.*"), merged);
        int idxCar = merged.indexOf("车一端内形及外径");
        int idxHeat = merged.indexOf("转热处理");
        assertTrue(idxCar >= 0 && idxHeat > idxCar, merged);
    }

    @Test
    void emptyDataRowsSurviveMarkdownRoundTrip() {
        String[][] grid = {
                {"工序号", "工序名称"},
                {"1", "车一端"},
                {"", ""},
                {"2", "铣"},
        };
        String md = MarkdownUtils.gridToMarkdown(grid, 4, 2, "工序目录表");
        String round = TablePopupManager.mergeMarkdownPreserveStructure(md, md);
        int tableLines = 0;
        for (String line : round.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            if (t.contains("-") && t.replaceAll("[|\\s\\-:]", "").isEmpty()) {
                continue;
            }
            tableLines++;
        }
        org.junit.jupiter.api.Assertions.assertEquals(4, tableLines, round);
        int car = round.indexOf("车一端");
        int mill = round.indexOf("铣");
        org.junit.jupiter.api.Assertions.assertTrue(car >= 0 && mill > car, round);
        org.junit.jupiter.api.Assertions.assertTrue(round.substring(car, mill).contains("|"), round);
    }
}
