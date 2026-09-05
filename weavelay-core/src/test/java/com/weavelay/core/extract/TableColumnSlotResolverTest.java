package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.PairMode;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.pair.TableColumnCellRegion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableColumnSlotResolverTest {

    @Test
    void resolvesOnlyDetectedColumnsFromDefinition() {
        // 模拟 catalog 中配置了多个 tbl_* 列, 页面上只检测到部分
        PageKindDefinition def = new PageKindDefinition(
                "process_catalog",
                "工序目录",
                10,
                new PageRuleConfig(80, 80, "", PairMode.VERTICAL, 80, 120),
                List.of(
                        new SlotDefinition("product_code", "产品代号", 1),
                        new SlotDefinition("tbl_supply_status", "供应状态", 5),
                        new SlotDefinition("tbl_blank_type", "毛坯种类", 6),
                        new SlotDefinition("tbl_blank_weight", "毛坯重量kg", 8)));

        // OCR 只有2个列头
        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯重量kg", 300, 200, 400, 220));

        List<SlotValue> slots = TableColumnSlotResolver.resolve(rows, def);

        // 只返回 OCR 中实际检测到的列, 未检测到的不出现
        assertEquals(2, slots.size());
        SlotValue blankWeight = slots.stream()
                .filter(s -> "tbl_blank_weight".equals(s.getSlotCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("", blankWeight.getValue());
        assertTrue(TableColumnCellRegion.isCellRegion(blankWeight.getValueRow()));
    }

    @Test
    void returnsEmptyWhenNoTblColumnsInDefinition() {
        PageKindDefinition def = new PageKindDefinition(
                "cover",
                "封面",
                1,
                new PageRuleConfig(80, 80, "", PairMode.VERTICAL, 80, 120),
                List.of(
                        new SlotDefinition("product_code", "产品代号", 1)));

        List<OcrRow> rows = List.of(
                row("供应状态", 100, 200, 180, 220),
                row("毛坯重量kg", 300, 200, 400, 220));

        // 定义中没有 tbl_* 列 → 返回空
        List<SlotValue> slots = TableColumnSlotResolver.resolve(rows, def);
        assertEquals(0, slots.size());
    }

    private static OcrRow row(String text, double x1, double y1, double x2, double y2) {
        return new OcrRow("p1", text, 1.0, x1, y1, x2, y2, null);
    }
}
