package com.weavelay.core.page;

/**
 * 文档族: 同一套页型与规则集.
 */
public enum DocumentFamily {

    PROCESS_SPEC(
            "process_spec",
            "工艺规程",
            "Process Specification",
            "工艺规程类 PDF 文档");

    private final String code;
    private final String name;
    private final String englishName;
    private final String description;

    DocumentFamily(String code, String name, String englishName, String description) {
        this.code = code;
        this.name = name;
        this.englishName = englishName;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getEnglishName() {
        return englishName;
    }

    public String getDescription() {
        return description;
    }

    /** @deprecated 使用 {@link #getEnglishName()} */
    public String getDisplayName() {
        return englishName;
    }

    public static DocumentFamily fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (DocumentFamily family : values()) {
            if (family.code.equals(code)) {
                return family;
            }
        }
        return null;
    }
}
