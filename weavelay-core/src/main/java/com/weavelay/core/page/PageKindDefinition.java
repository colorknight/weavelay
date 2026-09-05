package com.weavelay.core.page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 页型完整定义: 列表顺序、合并规则、槽位.
 */
public final class PageKindDefinition {

    private final PageKind kind;
    private final String code;
    private final String displayName;
    private final int classifyPriority;
    private final PageRuleConfig rule;
    private final List<SlotDefinition> slots;

    public PageKindDefinition(
            PageKind kind,
            String displayName,
            int classifyPriority,
            PageRuleConfig rule,
            List<SlotDefinition> slots) {
        this(kind, kind == null ? PageKind.UNKNOWN.getCode() : kind.getCode(), displayName,
                classifyPriority, rule, slots);
    }

    public PageKindDefinition(
            String code,
            String displayName,
            int classifyPriority,
            PageRuleConfig rule,
            List<SlotDefinition> slots) {
        this(PageKind.fromCode(code), code, displayName, classifyPriority, rule, slots);
    }

    private PageKindDefinition(
            PageKind kind,
            String code,
            String displayName,
            int classifyPriority,
            PageRuleConfig rule,
            List<SlotDefinition> slots) {
        this.kind = kind == null ? PageKind.UNKNOWN : kind;
        this.code = code == null ? PageKind.UNKNOWN.getCode() : code;
        this.displayName = displayName;
        this.classifyPriority = classifyPriority;
        this.rule = rule;
        this.slots = slots == null
                ? Collections.<SlotDefinition>emptyList()
                : Collections.unmodifiableList(new ArrayList<SlotDefinition>(slots));
    }

    public PageKind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 列表排序权重（设置里上移/下移），不再用于自动分类。 */
    public int getClassifyPriority() {
        return classifyPriority;
    }

    public PageRuleConfig getRule() {
        return rule;
    }

    public List<SlotDefinition> getSlots() {
        return slots;
    }
}
