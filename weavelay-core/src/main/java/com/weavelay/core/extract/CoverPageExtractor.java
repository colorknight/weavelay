package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeaveRow;
import com.weavelay.core.page.PageClassifier;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.pair.LabelValuePairer;
import com.weavelay.core.pair.PairMatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 封面页: 工序名 + 横向 label-value 槽位.
 */
public final class CoverPageExtractor implements PageExtractor {

    @Override
    public PageKind supportedKind() {
        return PageKind.COVER;
    }

    @Override
    public PageExtractResult extract(
            String streamName,
            List<OcrRow> pageRows,
            PageKindDefinition definition) {
        PageRuleConfig rule = definition.getRule();
        double rowTolerance = rule.getRowTolerance();

        List<SlotValue> slots = new ArrayList<SlotValue>();
        slots.add(new SlotValue("process_name", "工序名称", findProcessName(pageRows, definition)));

        for (SlotDefinition slot : definition.getSlots()) {
            PairMatch match = LabelValuePairer.pairHorizontal(pageRows, slot.getSlotLabel(), rowTolerance);
            slots.add(new SlotValue(
                    slot.getSlotCode(),
                    slot.getSlotLabel(),
                    match.getValue(),
                    match.getLabelRow(),
                    match.getValueRow()));
        }

        List<WeaveRow> weaveRows = toWeaveRows(streamName, slots);
        return new PageExtractResult(PageKind.COVER, slots, weaveRows);
    }

    private static String findProcessName(List<OcrRow> pageRows, PageKindDefinition definition) {
        Set<String> skip = new HashSet<String>();
        skip.add(PageClassifier.normalize(definition.getDisplayName()));
        for (SlotDefinition slot : definition.getSlots()) {
            skip.add(PageClassifier.normalize(slot.getSlotLabel()));
        }
        for (OcrRow row : pageRows) {
            String feat = row.getFeature();
            if (feat == null || feat.trim().isEmpty()) {
                continue;
            }
            String norm = PageClassifier.normalize(feat);
            if (skip.contains(norm)) {
                continue;
            }
            if (norm.matches("\\d+")) {
                continue;
            }
            if (row.getStartY() > 700) {
                return feat.trim();
            }
        }
        return "";
    }

    static List<WeaveRow> toWeaveRows(String streamName, List<SlotValue> slots) {
        List<WeaveRow> rows = new ArrayList<WeaveRow>();
        int seq = 1;
        for (SlotValue slot : slots) {
            if (slot.getValue() == null || slot.getValue().trim().isEmpty()) {
                continue;
            }
            rows.add(new WeaveRow(streamName, slot.getSlotLabel() + slot.getValue(), seq++));
        }
        return rows;
    }
}
