package com.weavelay.core.page;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SlotDefinitionFormatTest {

    @Test
    void roundTripsLegacyKeyLabelLines() {
        List<SlotDefinition> slots = Arrays.asList(
                new SlotDefinition("product_code", "产品代号", 1),
                new SlotDefinition("part_name", "零部件名称", 2));
        String text = SlotDefinitionFormat.format(slots);
        assertEquals("product_code=产品代号\npart_name=零部件名称", text);
        List<SlotDefinition> parsed = SlotDefinitionFormat.parse(text);
        assertEquals(2, parsed.size());
        assertEquals("product_code", parsed.get(0).getSlotCode());
        assertEquals("零部件名称", parsed.get(1).getSlotLabel());
    }

    @Test
    void parsesLineRefAndPlaceholderRows() {
        List<SlotDefinition> parsed = SlotDefinitionFormat.parse(
                "产品代号=#4\n零部件名称=\n零部件代号\npart_name=名称");
        assertEquals(4, parsed.size());
        assertEquals("产品代号", parsed.get(0).getSlotLabel());
        assertEquals(Integer.valueOf(4), parsed.get(0).getLineRefOrNull());
        assertEquals("零部件名称", parsed.get(1).getSlotLabel());
        assertNull(parsed.get(1).getLineRefOrNull());
        assertEquals("零部件代号", parsed.get(2).getSlotLabel());
        assertEquals("part_name", parsed.get(3).getSlotCode());
    }

    @Test
    void formatsLineRefAndPlaceholderRows() {
        List<SlotDefinition> slots = Arrays.asList(
                new SlotDefinition("#4", "产品代号", 1),
                new SlotDefinition("pending_2", "零部件名称", 2));
        assertEquals("产品代号=#4\n零部件名称=", SlotDefinitionFormat.format(slots));
    }

    @Test
    void resolvesOutputFromMergedLines() {
        List<SlotDefinition> slots = Arrays.asList(
                new SlotDefinition("#2", "产品代号", 1),
                new SlotDefinition("pending_2", "备注", 2));
        List<com.weavelay.core.model.WeaveRow> merged = Arrays.asList(
                row(1, "工艺规程"),
                row(2, "ABC-1.2A"));
        List<com.weavelay.core.model.SlotValue> values = SlotOutputResolver.resolve(slots, merged);
        assertEquals(2, values.size());
        assertEquals("ABC-1.2A", values.get(0).getValue());
        assertEquals("", values.get(1).getValue());
    }

    private static com.weavelay.core.model.WeaveRow row(int seq, String text) {
        return new com.weavelay.core.model.WeaveRow("p1", text, seq);
    }
}
