package com.weavelay.core.output;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyOutputDefinitionTextTest {

    @Test
    void roundTripSample() {
        FamilyOutputDefinition sample = FamilyOutputDefinitionFormat.sample();
        String text = FamilyOutputDefinitionText.format(sample);
        assertTrue(text.contains("工序[]:"), text);
        assertTrue(text.contains("机加工序:"), text);
        assertTrue(text.contains("→"), text);

        FamilyOutputDefinition again = FamilyOutputDefinitionText.parse(text);
        assertEquals("零件", again.root);
        assertEquals(3, again.product.size());
        assertTrue(again.collections.containsKey("工序"));
        assertEquals("工序目录表", again.collections.get("工序").sourceTable);
        assertEquals(3, again.collections.get("工序").columns.size());
        assertTrue(again.collections.get("工序").cards.containsKey("机加工序"));
        assertEquals(3, again.pageWrites.size());
        assertEquals("setScalars", again.pageWrites.get(0).mode);
        assertEquals("mergeTable", again.pageWrites.get(1).mode);
        assertEquals("patchEntry", again.pageWrites.get(2).mode);
        assertEquals("机加工序", again.pageWrites.get(2).card);
    }

    @Test
    void parseTreeBelonging() {
        String text = """
                零件:
                  产品代号
                  零部件名称

                  工序[]:
                    来源: 工序目录表
                    键: 工序号
                    工序号
                    工序名称

                    机加工序:

                页写入:
                  封面 → 零件
                  工序目录 → 零件.工序[]
                  机加工序卡片 → 零件.工序[].机加工序  键=工序号
                """;
        FamilyOutputDefinition def = FamilyOutputDefinitionText.parse(text);
        assertEquals("零件", def.root);
        assertEquals(2, def.product.size());
        assertEquals("工序", def.collections.keySet().iterator().next());
        assertTrue(def.collections.get("工序").cards.containsKey("机加工序"));
        assertEquals("零件", def.pageWrites.get(0).target);
        assertEquals("工序", def.pageWrites.get(1).collection);
        assertEquals("patchEntry", def.pageWrites.get(2).mode);
        assertEquals("工序号", def.pageWrites.get(2).matchKey);
    }
}
