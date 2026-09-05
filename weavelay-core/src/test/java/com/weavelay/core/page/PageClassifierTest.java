package com.weavelay.core.page;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PageClassifierTest {

    @Test
    void parseLineRef() {
        assertEquals(Integer.valueOf(2), PageClassifier.parseLineRef("#2"));
        assertNull(PageClassifier.parseLineRef("工序目录"));
        assertNull(PageClassifier.parseLineRef("#0"));
    }

    @Test
    void normalizeStripsWhitespace() {
        assertEquals("工序目录", PageClassifier.normalize("工 序\n目录"));
        assertEquals("", PageClassifier.normalize(null));
    }
}
