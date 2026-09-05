package com.weavelay.core.page;

/**
 * 页型代码 (锚点与规则由 RuleCatalog 配置, 不在此写死业务文案).
 */
public enum PageKind {

    COVER("cover"),
    PROCESS_CATALOG("process_catalog"),
    PROCESS_DRAWING("process_drawing"),
    MACHINING_CARD("machining_card"),
    INSPECTION_CARD("inspection_card"),
    AUXILIARY_CARD("auxiliary_card"),
    UNKNOWN("unknown");

    private final String code;

    PageKind(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static PageKind fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        for (PageKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        return UNKNOWN;
    }
}
