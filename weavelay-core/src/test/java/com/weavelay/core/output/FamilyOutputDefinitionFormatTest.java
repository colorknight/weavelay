package com.weavelay.core.output;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyOutputDefinitionFormatTest {

    @Test
    void roundTripSample() {
        FamilyOutputDefinition sample = FamilyOutputDefinitionFormat.sample();
        String json = FamilyOutputDefinitionFormat.format(sample);
        FamilyOutputDefinition again = FamilyOutputDefinitionFormat.parse(json);
        assertEquals(3, again.product.size());
        assertTrue(again.tables.containsKey("工序目录表"));
        assertEquals(3, again.pageWrites.size());
    }
}
