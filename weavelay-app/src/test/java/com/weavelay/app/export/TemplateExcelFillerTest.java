package com.weavelay.app.export;

import com.weavelay.core.model.SlotValue;
import com.weavelay.core.store.TemplateRule;
import javafx.scene.control.TreeItem;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemplateExcelFillerTest {

    @TempDir
    Path temp;

    @Test
    void placeholdersLinkCatalogRowsToTargetSheets() throws Exception {
        Path tpl = temp.resolve("tpl.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("任意页");
            Row r0 = sh.createRow(0);
            r0.createCell(3).setCellValue("代号={{产品代号}}");
            Row r4 = sh.createRow(4);
            r4.createCell(1).setCellValue("{{材料表.零件重量kg}}");
            r4.createCell(2).setCellValue("{{材料表,2,零件重量kg}}");
            r4.createCell(3).setCellValue("{{材料表.2.其它}}");
            Row r10 = sh.createRow(10);
            r10.createCell(2).setCellValue("工序号");
            r10.createCell(3).setCellValue("工序名称");
            Row r11 = sh.createRow(11);
            r11.createCell(2).setCellValue("{{+工序目录表,工序号,工序名称}}");
            Sheet mach = wb.createSheet("机加工序工步");
            mach.createRow(0).createCell(0).setCellValue("mach-mark");
            Sheet insp = wb.createSheet("检验工序工步");
            insp.createRow(0).createCell(0).setCellValue("insp-mark");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }

        Path out = temp.resolve("out.xlsx");
        TemplateExcelFiller.ensureOutputFromTemplate(tpl, out);
        List<TemplateRule> rules = List.of(
                new TemplateRule("机加工序工步", "^\\d+$", 10),
                new TemplateRule("检验工序工步", "Y", 20)
        );
        TemplateExcelFiller.fill(out, tpl, List.of(
                leaf("product_code", "产品代号", "WB001"),
                table("mat", "材料表",
                        "|零件重量kg|其它|\n|---|---|\n|12.5|x|\n|99|y|"),
                table("cat", "工序目录表",
                        "|工序号|工序名称|\n|---|---|\n|10|下料|\n|2Y|专检|")
        ), rules);

        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(out))) {
            Sheet sh = wb.getSheet("任意页");
            assertNotNull(sh);
            assertEquals("代号=WB001", fmt.formatCellValue(sh.getRow(0).getCell(3)));
            assertEquals("12.5", fmt.formatCellValue(sh.getRow(4).getCell(1)));
            assertEquals("99", fmt.formatCellValue(sh.getRow(4).getCell(2)));
            assertEquals("y", fmt.formatCellValue(sh.getRow(4).getCell(3)));
            assertEquals("10", fmt.formatCellValue(sh.getRow(11).getCell(2)));
            assertEquals("下料", fmt.formatCellValue(sh.getRow(11).getCell(3)));
            assertEquals("2Y", fmt.formatCellValue(sh.getRow(12).getCell(2)));
            assertEquals("专检", fmt.formatCellValue(sh.getRow(12).getCell(3)));
            Hyperlink link10 = sh.getRow(11).getCell(2).getHyperlink();
            assertNotNull(link10);
            assertEquals("'机加工序工步'!A1", link10.getAddress());
            Hyperlink link10Name = sh.getRow(11).getCell(3).getHyperlink();
            assertNotNull(link10Name);
            assertEquals("'机加工序工步'!A1", link10Name.getAddress());
            Hyperlink link2y = sh.getRow(12).getCell(2).getHyperlink();
            assertNotNull(link2y);
            assertEquals("'检验工序工步'!A1", link2y.getAddress());
            // 不按工序名克隆子 Sheet
            assertNull(wb.getSheet("下料"));
            assertNull(wb.getSheet("专检"));
            assertEquals("mach-mark", fmt.formatCellValue(wb.getSheet("机加工序工步").getRow(0).getCell(0)));
            assertEquals("insp-mark", fmt.formatCellValue(wb.getSheet("检验工序工步").getRow(0).getCell(0)));
        }
    }

    @Test
    void sameTypeRowsShareOneTargetSheet() throws Exception {
        Path tpl = temp.resolve("tpl-merge.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("目录");
            sh.createRow(0).createCell(0).setCellValue("工序号");
            sh.getRow(0).createCell(1).setCellValue("工序名称");
            sh.createRow(1).createCell(0).setCellValue("{{+工序目录表,工序号,工序名称}}");
            wb.createSheet("机加工序工步").createRow(0).createCell(0).setCellValue("{{产品代号}}");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }
        Path out = temp.resolve("out-merge.xlsx");
        TemplateExcelFiller.ensureOutputFromTemplate(tpl, out);
        TemplateExcelFiller.fill(out, tpl, List.of(
                leaf("product_code", "产品代号", "P9"),
                table("cat", "工序目录表",
                        "|工序号|工序名称|\n|---|---|\n|10|下料|\n|20|车削|")
        ), List.of(new TemplateRule("机加工序工步", "^\\d+$", 10)));

        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(out))) {
            Sheet cat = wb.getSheet("目录");
            assertEquals("'机加工序工步'!A1", cat.getRow(1).getCell(0).getHyperlink().getAddress());
            assertEquals("'机加工序工步'!A1", cat.getRow(2).getCell(0).getHyperlink().getAddress());
            assertNull(wb.getSheet("下料"));
            assertNull(wb.getSheet("车削"));
            assertEquals("P9", fmt.formatCellValue(wb.getSheet("机加工序工步").getRow(0).getCell(0)));
        }
    }

    @Test
    void nameColumnPlusDoesNotSpawnNamedSheet() throws Exception {
        Path tpl = temp.resolve("tpl-concat.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("目录");
            sh.createRow(10).createCell(2).setCellValue("工序号");
            sh.getRow(10).createCell(3).setCellValue("工序名称");
            sh.createRow(11).createCell(2).setCellValue("{{+工序目录表,工序号,工序号+工序名称}}");
            wb.createSheet("机加工序工步").createRow(0).createCell(0).setCellValue("ok");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }
        Path out = temp.resolve("out-concat.xlsx");
        TemplateExcelFiller.ensureOutputFromTemplate(tpl, out);
        TemplateExcelFiller.fill(out, tpl, List.of(
                table("cat", "工序目录表",
                        "|工序号|工序名称|\n|---|---|\n|10|下料|")
        ), List.of(new TemplateRule("机加工序工步", "^\\d+$", 10)));

        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(out))) {
            assertNull(wb.getSheet("10下料"));
            Hyperlink link = wb.getSheet("目录").getRow(11).getCell(2).getHyperlink();
            assertNotNull(link);
            assertEquals("'机加工序工步'!A1", link.getAddress());
            // 拼接命名列不加链接
            assertNull(wb.getSheet("目录").getRow(11).getCell(3).getHyperlink());
            assertEquals("10", fmt.formatCellValue(wb.getSheet("目录").getRow(11).getCell(2)));
        }
    }

    @Test
    void resolveSheetNameJoinsWithPlus() {
        List<String> header = List.of("工序号", "工序名称");
        List<String> line = List.of("10", "下料");
        assertEquals("10下料", TemplateExcelFiller.resolveSheetName(
                header, line, "工序号+工序名称", "fb"));
        assertEquals("下料", TemplateExcelFiller.resolveSheetName(
                header, line, "工序名称", "fb"));
        assertEquals("10", TemplateExcelFiller.resolveSheetName(
                header, line, "不存在", "10"));
    }

    @Test
    void resolveTargetSheetNameUsesExactSheetName() throws Exception {
        Path tpl = temp.resolve("tpl-target.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("封面及目录");
            wb.createSheet("机加工序工步");
            wb.createSheet("检验工序工步");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }
        assertEquals("机加工序工步", TemplateExcelFiller.resolveTargetSheetName("机加工序工步"));
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(tpl))) {
            assertEquals("机加工序工步",
                    TemplateExcelFiller.resolveTargetSheetName("机加工序工步", wb, tpl));
            assertEquals("检验工序工步",
                    TemplateExcelFiller.resolveTargetSheetName("检验工序工步", wb, tpl));
            assertEquals("",
                    TemplateExcelFiller.resolveTargetSheetName("机械加工", wb, tpl));
            assertEquals("",
                    TemplateExcelFiller.resolveTargetSheetName("检验", wb, tpl));
        }
    }

    @Test
    void missingPlaceholdersBecomeEmpty() throws Exception {
        Path tpl = temp.resolve("tpl-empty.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("封面");
            sh.createRow(0).createCell(0).setCellValue("{{材料表.零件重量kg}}");
            sh.createRow(1).createCell(0).setCellValue("有值={{产品代号}} 无值={{不存在字段}}");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }
        Path out = temp.resolve("out-empty.xlsx");
        TemplateExcelFiller.ensureOutputFromTemplate(tpl, out);
        TemplateExcelFiller.SlotMaps maps = new TemplateExcelFiller.SlotMaps();
        maps.scalars.put("产品代号", "ABC");
        TemplateExcelFiller.fill(out, tpl, maps, List.of());
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(out))) {
            Sheet sh = wb.getSheet("封面");
            assertEquals("", fmt.formatCellValue(sh.getRow(0).getCell(0)));
            assertEquals("有值=ABC 无值=", fmt.formatCellValue(sh.getRow(1).getCell(0)));
        }
    }

    @Test
    void fillFromSlotMapsLinksByTargetAndLegacyTemplate() throws Exception {
        Path tpl = temp.resolve("tpl-coll.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("封面");
            sh.createRow(0).createCell(0).setCellValue("工序号");
            sh.getRow(0).createCell(1).setCellValue("工序名称");
            sh.getRow(0).createCell(2).setCellValue("技术要求");
            sh.createRow(1).createCell(0).setCellValue("{{+工序目录表,工序号,工序名称}}");
            wb.createSheet("机加工序工步").createRow(0).createCell(0).setCellValue("mach");
            wb.createSheet("检验工序工步").createRow(0).createCell(0).setCellValue("insp");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }
        Path out = temp.resolve("out-coll.xlsx");
        TemplateExcelFiller.ensureOutputFromTemplate(tpl, out);
        var doc = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "工序目录": [
                    { "工序号": "1", "工序名称": "车一端", "设备.型号": "CW3333" },
                    { "工序号": "1Y", "工序名称": "检验" }
                  ]
                }
                """);
        TemplateExcelFiller.SlotMaps maps = JsonDocumentToSlotMaps.fromDocument(doc);
        List<TemplateRule> rules = DefinitionRouteRules.parse("""
                {
                  "route": [
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工", "target": "机加工序工步" },
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+Y$", "to": "检验", "template": "检验工序工步" }
                  ]
                }
                """);
        TemplateExcelFiller.fill(out, tpl, maps, rules);
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(out))) {
            Sheet cover = wb.getSheet("封面");
            assertEquals("1", fmt.formatCellValue(cover.getRow(1).getCell(0)));
            assertEquals("车一端", fmt.formatCellValue(cover.getRow(1).getCell(1)));
            assertEquals("", fmt.formatCellValue(cover.getRow(1).getCell(2)));
            assertEquals("'机加工序工步'!A1", cover.getRow(1).getCell(0).getHyperlink().getAddress());
            assertEquals("'检验工序工步'!A1", cover.getRow(2).getCell(0).getHyperlink().getAddress());
            assertNull(wb.getSheet("车一端"));
            assertNotNull(wb.getSheet("机加工序工步"));
            assertNotNull(wb.getSheet("检验工序工步"));
        }
    }

    @Test
    void missingTargetSkipsExcelLink() throws Exception {
        Path tpl = temp.resolve("tpl-no-target.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("封面");
            sh.createRow(0).createCell(0).setCellValue("工序号");
            sh.getRow(0).createCell(1).setCellValue("工序名称");
            sh.createRow(1).createCell(0).setCellValue("{{+工序目录表,工序号,工序名称}}");
            wb.createSheet("机加工序工步").createRow(0).createCell(0).setCellValue("mach");
            try (OutputStream out = Files.newOutputStream(tpl)) {
                wb.write(out);
            }
        }
        Path out = temp.resolve("out-no-target.xlsx");
        TemplateExcelFiller.ensureOutputFromTemplate(tpl, out);
        var doc = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "工序目录": [
                    { "工序号": "F1", "工序名称": "辅助工序" },
                    { "工序号": "1", "工序名称": "车削" }
                  ]
                }
                """);
        TemplateExcelFiller.SlotMaps maps = JsonDocumentToSlotMaps.fromDocument(doc);
        List<TemplateRule> rules = DefinitionRouteRules.parse("""
                {
                  "route": [
                    { "from": "工序目录", "field": "工序号", "match": "^[FW]\\\\d+$", "to": "辅助" },
                    { "from": "工序目录", "field": "工序号", "match": "^\\\\d+$", "to": "机械加工", "target": "机加工序工步" }
                  ]
                }
                """);
        TemplateExcelFiller.fill(out, tpl, maps, rules);
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(Files.newInputStream(out))) {
            Sheet cover = wb.getSheet("封面");
            assertEquals("F1", fmt.formatCellValue(cover.getRow(1).getCell(0)));
            assertEquals("辅助工序", fmt.formatCellValue(cover.getRow(1).getCell(1)));
            assertEquals("1", fmt.formatCellValue(cover.getRow(2).getCell(0)));
            assertNull(cover.getRow(1).getCell(0).getHyperlink());
            assertNotNull(cover.getRow(2).getCell(0).getHyperlink());
            assertEquals("'机加工序工步'!A1", cover.getRow(2).getCell(0).getHyperlink().getAddress());
            assertNull(wb.getSheet("辅助工序"));
            assertNull(wb.getSheet("车削"));
            assertNotNull(wb.getSheet("机加工序工步"));
        }
    }

    private static TreeItem<SlotValue> leaf(String code, String label, String value) {
        return new TreeItem<>(new SlotValue(code, label, value));
    }

    private static TreeItem<SlotValue> table(String code, String label, String md) {
        return new TreeItem<>(new SlotValue(code, "*" + label + ":c1,c2", md));
    }
}
