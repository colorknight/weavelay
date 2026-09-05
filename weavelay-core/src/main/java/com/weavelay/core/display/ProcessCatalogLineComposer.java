package com.weavelay.core.display;

import com.weavelay.core.extract.ProcessCatalogTableColumns;
import com.weavelay.core.extract.TableColumnSlotResolver;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.page.PageClassifier;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.pair.TableColumnCellRegion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 页内行: 列头下插入数据行, 空 cell 显示占位符.
 * 列头由 PageKindDefinition 中的 tbl_* 槽位定义提供.
 */
public final class ProcessCatalogLineComposer {

    public static final String EMPTY_DISPLAY = "—";

    private ProcessCatalogLineComposer() {
    }

    public static List<SlotValue> composePageLines(
            List<OcrRow> sortedLines,
            List<SlotValue> tblSlots) {
        return composePageLines(sortedLines, tblSlots, null);
    }

    public static List<SlotValue> composePageLines(
            List<OcrRow> sortedLines,
            List<SlotValue> tblSlots,
            String pageKindCode) {
        Set<String> knownLabels = collectKnownLabels(tblSlots);
        if (!shouldInterleaveTableColumns(sortedLines, knownLabels)) {
            return composeOcrOnly(sortedLines);
        }
        Map<String, SlotValue> tblByLabel = indexTblSlots(tblSlots);
        fillMissingDefinitions(tblByLabel, sortedLines, knownLabels, tblSlots);

        List<SlotValue> lines = new ArrayList<SlotValue>();
        Set<String> usedTbl = new HashSet<String>();
        int seq = 1;
        for (OcrRow row : sortedLines) {
            if (row == null || row.getFeature() == null || row.getFeature().trim().isEmpty()) {
                continue;
            }
            lines.add(new SlotValue(
                    "line_" + seq,
                    "#" + seq,
                    row.getFeature().trim(),
                    null,
                    row));
            seq++;

            SlotValue under = matchUnderHeader(row, tblByLabel, usedTbl);
            if (under != null) {
                lines.add(toNumberedDataLine(under, seq));
                seq++;
                usedTbl.add(under.getSlotCode());
            }
        }
        return lines;
    }

    private static Set<String> collectKnownLabels(List<SlotValue> tblSlots) {
        Set<String> labels = new HashSet<String>();
        if (tblSlots == null) {
            return labels;
        }
        for (SlotValue slot : tblSlots) {
            if (slot != null && slot.getSlotLabel() != null) {
                labels.add(PageClassifier.normalize(slot.getSlotLabel()));
            }
        }
        return labels;
    }

    public static boolean shouldInterleaveTableColumns(List<OcrRow> sortedLines, Set<String> knownLabels) {
        if (knownLabels == null || knownLabels.isEmpty()) {
            return false;
        }
        return countKnownTableHeaders(sortedLines, knownLabels) >= 3;
    }

    static int countKnownTableHeaders(List<OcrRow> sortedLines, Set<String> knownLabels) {
        if (sortedLines == null || sortedLines.isEmpty()) {
            return 0;
        }
        int hits = 0;
        Set<String> seen = new HashSet<String>();
        for (OcrRow row : sortedLines) {
            if (row == null || row.getFeature() == null) {
                continue;
            }
            String key = PageClassifier.normalize(row.getFeature());
            if (isKnownTableHeader(key, knownLabels) && seen.add(key)) {
                hits++;
            }
        }
        return hits;
    }

    static boolean isKnownTableHeader(String normalizedFeature, Set<String> knownLabels) {
        return knownLabels != null && knownLabels.contains(normalizedFeature);
    }

    public static List<SlotValue> resolveTblSlots(
            List<OcrRow> pageRows,
            PageKindDefinition catalogDefinition) {
        if (pageRows == null || pageRows.isEmpty()) {
            return List.of();
        }
        List<SlotDefinition> columnDefs = TableColumnSlotResolver.tableColumnDefinitions(catalogDefinition);
        if (columnDefs.isEmpty()) {
            return List.of();
        }
        Set<String> knownLabels = columnDefs.stream()
                .map(d -> PageClassifier.normalize(d.getSlotLabel()))
                .collect(Collectors.toSet());
        if (countKnownTableHeaders(pageRows, knownLabels) < 3) {
            return List.of();
        }
        return TableColumnSlotResolver.resolve(pageRows, catalogDefinition);
    }

    public static List<OcrRow> virtualCellRows(List<SlotValue> tblSlots) {
        List<OcrRow> regions = new ArrayList<OcrRow>();
        if (tblSlots == null) {
            return regions;
        }
        for (SlotValue slot : tblSlots) {
            OcrRow valueRow = slot.getValueRow();
            if (valueRow != null && valueRow.hasBbox()
                    && TableColumnCellRegion.isCellRegion(valueRow)) {
                regions.add(valueRow);
            }
        }
        return regions;
    }

    public static String displayValue(SlotValue slot) {
        if (slot == null) {
            return "";
        }
        String value = slot.getValue();
        if (value == null || value.isEmpty()) {
            if (ProcessCatalogTableColumns.isTableColumnCode(slot.getSlotCode())) {
                return EMPTY_DISPLAY;
            }
            return "";
        }
        return value;
    }

    private static List<SlotValue> composeOcrOnly(List<OcrRow> sortedLines) {
        List<SlotValue> lines = new ArrayList<SlotValue>();
        int seq = 1;
        for (OcrRow row : sortedLines) {
            if (row == null || row.getFeature() == null || row.getFeature().trim().isEmpty()) {
                continue;
            }
            lines.add(new SlotValue(
                    "line_" + seq,
                    "#" + seq,
                    row.getFeature().trim(),
                    null,
                    row));
            seq++;
        }
        return lines;
    }

    private static Map<String, SlotValue> indexTblSlots(List<SlotValue> tblSlots) {
        Map<String, SlotValue> map = new HashMap<String, SlotValue>();
        if (tblSlots == null) {
            return map;
        }
        for (SlotValue slot : tblSlots) {
            if (slot == null || slot.getSlotLabel() == null) {
                continue;
            }
            map.put(PageClassifier.normalize(slot.getSlotLabel()), slot);
        }
        return map;
    }

    private static void fillMissingDefinitions(
            Map<String, SlotValue> tblByLabel,
            List<OcrRow> sortedLines,
            Set<String> knownLabels,
            List<SlotValue> tblSlots) {
        Map<String, SlotValue> templateByLabel = new HashMap<String, SlotValue>();
        if (tblSlots != null) {
            for (SlotValue slot : tblSlots) {
                if (slot != null && slot.getSlotLabel() != null) {
                    templateByLabel.put(PageClassifier.normalize(slot.getSlotLabel()), slot);
                }
            }
        }
        for (OcrRow row : sortedLines) {
            if (row == null || row.getFeature() == null) {
                continue;
            }
            String key = PageClassifier.normalize(row.getFeature());
            if (knownLabels.contains(key) && !tblByLabel.containsKey(key)) {
                SlotValue template = templateByLabel.get(key);
                if (template != null) {
                    tblByLabel.put(key, new SlotValue(
                            template.getSlotCode(),
                            template.getSlotLabel(),
                            "",
                            null,
                            null));
                }
            }
        }
    }

    private static SlotValue matchUnderHeader(
            OcrRow headerRow,
            Map<String, SlotValue> tblByLabel,
            Set<String> usedTbl) {
        String key = PageClassifier.normalize(headerRow.getFeature());
        SlotValue slot = tblByLabel.get(key);
        if (slot == null || slot.getSlotCode() == null || usedTbl.contains(slot.getSlotCode())) {
            return null;
        }
        return slot;
    }

    static SlotValue toNumberedDataLine(SlotValue slot, int seq) {
        return new SlotValue(
                slot.getSlotCode(),
                "#" + seq,
                "↳ " + slot.getSlotLabel() + "  " + displayValue(slot),
                slot.getLabelRow(),
                slot.getValueRow());
    }

    static SlotValue toDataLine(SlotValue slot) {
        return new SlotValue(
                slot.getSlotCode(),
                "↳ " + slot.getSlotLabel(),
                displayValue(slot),
                slot.getLabelRow(),
                slot.getValueRow());
    }
}
