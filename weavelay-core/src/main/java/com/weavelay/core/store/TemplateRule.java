package com.weavelay.core.store;

/**
 * Excel 同类写入规则：{@link #getName()} 为模板里已有 target Sheet 的完整名称（与 Excel 一致），
 * 正则匹配目录行某列。通常由输出定义 JSON 的 {@code route[]} 解析得到。
 * <ul>
 *   <li>{@code target}（旧名 {@code template}）：要写入/目录跳转的 Sheet 全名</li>
 *   <li>无 {@code target} 的 route 仅参与确认 JSON 分流，不进本规则表</li>
 * </ul>
 * <p>同类工序合并写入同一 Sheet，不再按条目克隆子页。
 */
public final class TemplateRule {

    private final long id;
    private final String name;
    private final String regex;
    private final int sortOrder;

    public TemplateRule(long id, String name, String regex, int sortOrder) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.regex = regex == null ? "" : regex;
        this.sortOrder = sortOrder;
    }

    public TemplateRule(String name, String regex, int sortOrder) {
        this(0L, name, regex, sortOrder);
    }

    public long getId() {
        return id;
    }

    /** Excel {@code route.target} Sheet 全名。 */
    public String getName() {
        return name;
    }

    public String getRegex() {
        return regex;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
