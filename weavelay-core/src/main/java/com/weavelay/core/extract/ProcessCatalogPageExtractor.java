package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeaveRow;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.pair.LabelValuePairer;
import com.weavelay.core.pair.OcrCellValueCollector;
import com.weavelay.core.pair.PairMatch;
import com.weavelay.core.pair.TableColumnValueCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工序目录页: 圈号 + 表头 label-value 槽位.
 */
public final class ProcessCatalogPageExtractor implements PageExtractor {

    @Override
    public PageKind supportedKind() {
        return PageKind.PROCESS_CATALOG;
    }

    @Override
    public PageExtractResult extract(
            String streamName,
            List<OcrRow> pageRows,
            PageKindDefinition definition) {
        PageRuleConfig rule = definition.getRule();
        double colTolerance = rule.getColTolerance();
        double rowTolerance = rule.getRowTolerance();

        List<SlotValue> slots = new ArrayList<SlotValue>();
        slots.add(new SlotValue("page_no", "圈号", findPageNo(pageRows)));

        List<SlotDefinition> tableColumnSlots = TableColumnSlotResolver.tableColumnDefinitions(definition);
        for (SlotDefinition slot : definition.getSlots()) {
            if (ProcessCatalogTableColumns.isTableColumnCode(slot.getSlotCode())) {
                continue;
            }
            PairMatch match = pairSlot(pageRows, slot, colTolerance, rowTolerance);
            slots.add(toSlotValue(slot, match));
        }
        if (!tableColumnSlots.isEmpty()) {
            slots.addAll(extractTableColumns(pageRows, tableColumnSlots, rowTolerance));
        }

        List<WeaveRow> weaveRows = CoverPageExtractor.toWeaveRows(streamName, slots);
        return new PageExtractResult(PageKind.PROCESS_CATALOG, slots, weaveRows);
    }

    private static PairMatch pairSlot(
            List<OcrRow> pageRows,
            SlotDefinition slot,
            double colTolerance,
            double rowTolerance) {
        if ("material".equals(slot.getSlotCode())) {
            return OcrCellValueCollector.collectBesideLabel(
                    pageRows,
                    slot.getSlotLabel(),
                    rowTolerance,
                    colTolerance * 2.5);
        }
        return LabelValuePairer.pairVertical(pageRows, slot.getSlotLabel(), colTolerance);
    }

    private static List<SlotValue> extractTableColumns(
            List<OcrRow> pageRows,
            List<SlotDefinition> tableColumnSlots,
            double rowTolerance) {
        List<String> labels = tableColumnSlots.stream()
                .map(SlotDefinition::getSlotLabel)
                .collect(Collectors.toList());
        Map<String, PairMatch> matches = TableColumnValueCollector.collectColumns(
                pageRows, labels, rowTolerance);
        List<SlotValue> slots = new ArrayList<SlotValue>();
        for (SlotDefinition slot : tableColumnSlots) {
            PairMatch match = matches.getOrDefault(slot.getSlotLabel(), PairMatch.empty());
            slots.add(toSlotValue(slot, match));
        }
        return slots;
    }

    private static SlotValue toSlotValue(SlotDefinition slot, PairMatch match) {
        return new SlotValue(
                slot.getSlotCode(),
                slot.getSlotLabel(),
                match.getValue(),
                match.getLabelRow(),
                match.getValueRow());
    }

    private static String findPageNo(List<OcrRow> pageRows) {
        for (OcrRow row : pageRows) {
            String feat = row.getFeature();
            if (feat == null) {
                continue;
            }
            String trimmed = feat.trim();
            if (trimmed.matches("\\d{1,3}")) {
                return trimmed;
            }
        }
        return "";
    }
}
