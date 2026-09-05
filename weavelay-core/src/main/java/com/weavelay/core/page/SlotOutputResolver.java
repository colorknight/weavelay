package com.weavelay.core.page;

import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeaveRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按输出定义中的 {@code #N} 行引用，从 merge 行解析本页输出值.
 */
public final class SlotOutputResolver {

    private SlotOutputResolver() {
    }

    public static List<SlotValue> resolve(List<SlotDefinition> slots, List<WeaveRow> mergedRows) {
        if (slots == null || slots.isEmpty()) {
            return Collections.emptyList();
        }
        List<SlotValue> values = new ArrayList<SlotValue>(slots.size());
        for (SlotDefinition slot : slots) {
            Integer lineNo = slot.getLineRefOrNull();
            String value = lineNo == null ? "" : valueAtLine(mergedRows, lineNo);
            values.add(new SlotValue(slot.getSlotCode(), slot.getSlotLabel(), value));
        }
        return values;
    }

    private static String valueAtLine(List<WeaveRow> merged, int lineNo) {
        if (merged == null || lineNo < 1) {
            return "";
        }
        for (WeaveRow row : merged) {
            if (row.getSeq() == lineNo) {
                return row.getFeature() == null ? "" : row.getFeature();
            }
        }
        return "";
    }
}
