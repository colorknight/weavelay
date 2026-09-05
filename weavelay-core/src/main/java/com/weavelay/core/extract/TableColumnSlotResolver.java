package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.pair.PairMatch;
import com.weavelay.core.pair.TableColumnValueCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表格列槽: 由列头 OCR + 几何区域生成 (含空单元格占位).
 * 列定义完全由 PageKindDefinition 提供, 不写死业务列头.
 */
public final class TableColumnSlotResolver {

    private static final double DEFAULT_ROW_TOLERANCE = 80.0;

    private TableColumnSlotResolver() {
    }

    public static List<SlotDefinition> tableColumnDefinitions(PageKindDefinition definition) {
        if (definition == null) {
            return List.of();
        }
        List<SlotDefinition> fromCatalog = new ArrayList<SlotDefinition>();
        for (SlotDefinition slot : definition.getSlots()) {
            if (ProcessCatalogTableColumns.isTableColumnCode(slot.getSlotCode())) {
                fromCatalog.add(slot);
            }
        }
        return fromCatalog;
    }

    public static List<SlotValue> resolve(List<OcrRow> pageRows, PageKindDefinition definition) {
        List<SlotDefinition> columns = tableColumnDefinitions(definition);
        if (columns.isEmpty() || pageRows == null || pageRows.isEmpty()) {
            return List.of();
        }
        double rowTolerance = DEFAULT_ROW_TOLERANCE;
        PageRuleConfig rule = definition.getRule();
        if (rule != null && rule.getRowTolerance() > 0) {
            rowTolerance = rule.getRowTolerance();
        }
        List<String> labels = columns.stream()
                .map(SlotDefinition::getSlotLabel)
                .collect(Collectors.toList());
        Map<String, PairMatch> matches = TableColumnValueCollector.collectColumns(
                pageRows, labels, rowTolerance);
        List<SlotValue> slots = new ArrayList<SlotValue>();
        for (SlotDefinition column : columns) {
            PairMatch match = matches.get(column.getSlotLabel());
            if (match == null || match.getLabelRow() == null) {
                continue;
            }
            slots.add(new SlotValue(
                    column.getSlotCode(),
                    column.getSlotLabel(),
                    match.getValue(),
                    match.getLabelRow(),
                    match.getValueRow()));
        }
        return slots;
    }
}
