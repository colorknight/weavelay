package com.weavelay.core.extract;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.page.SlotDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于标签文字的正则提取器。
 *
 * <p>将全页 OCR 文字按阅读顺序拼接，用 slotLabel 作为正则关键词匹配后面的值。
 * 不受坐标偏移、表格行数、布局漏检的影响。</p>
 *
 * <p>规则格式：slotLabel = 要搜索的标签文字，如"产品代号"、"材料"等。
 * 自动生成正则：{@code 产品代号[:：]?\s*(.+)}。</p>
 */
public final class RuleExtractor {

    private RuleExtractor() {
    }

    /**
     * 从 OCR 行列表中按规则提取所有槽位值。
     *
     * @param slots  输出槽位定义（slotLabel 作为搜索关键词）
     * @param ocrRows 当前页 OCR 文字行
     * @return 提取结果列表，每个槽位含值和置信度
     */
    public static List<SlotValue> extract(List<SlotDefinition> slots, List<OcrRow> ocrRows) {
        if (slots == null || slots.isEmpty() || ocrRows == null || ocrRows.isEmpty()) {
            return List.of();
        }

        // 按阅读顺序拼接全页文字
        String flat = flatText(ocrRows);

        List<SlotValue> results = new ArrayList<>();
        for (SlotDefinition slot : slots) {
            // 跳过特殊绑定类型
            if (slot.isLineRefBinding()) {
                continue;
            }
            String label = slot.getSlotLabel();
            if (label == null || label.trim().isEmpty()) {
                results.add(new SlotValue(slot.getSlotCode(), slot.getSlotLabel(), "", null, null));
                continue;
            }

            // 生成正则：标签词 + 可选冒号 + 值
            String regex = Pattern.quote(label.trim()) + "[:：=]?\\s*([^\\s].{0,80})";
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(flat);
            if (m.find()) {
                String value = m.group(1).trim();
                results.add(new SlotValue(slot.getSlotCode(), slot.getSlotLabel(), value, null, null));
            } else {
                // 未匹配，空值，标记低置信度
                results.add(new SlotValue(slot.getSlotCode(), slot.getSlotLabel(), "", null, null));
            }
        }
        return results;
    }

    /** 把 OCR 行按阅读顺序拼接成一行文本。 */
    static String flatText(List<OcrRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (OcrRow row : rows) {
            String f = row.getFeature();
            if (f != null && !f.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(f.trim());
            }
        }
        return sb.toString();
    }
}
