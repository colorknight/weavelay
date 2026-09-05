package com.weavelay.core.store;

public final class FamilyRecord {

    private final String code;
    private final String name;
    private final String englishName;
    private final String description;
    /** 该文件类型对应的 Excel 输出模板路径；空表示未配置。 */
    private final String excelTemplatePath;

    public FamilyRecord(String code, String name, String englishName, String description) {
        this(code, name, englishName, description, "");
    }

    public FamilyRecord(
            String code,
            String name,
            String englishName,
            String description,
            String excelTemplatePath) {
        this.code = code;
        this.name = name == null ? "" : name;
        this.englishName = englishName == null ? "" : englishName;
        this.description = description == null ? "" : description;
        this.excelTemplatePath = excelTemplatePath == null ? "" : excelTemplatePath;
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

    public String getExcelTemplatePath() {
        return excelTemplatePath;
    }

    public boolean hasExcelTemplate() {
        return !excelTemplatePath.isBlank();
    }

    /** @deprecated 使用 {@link #getEnglishName()} */
    public String getDisplayName() {
        return englishName;
    }

    @Override
    public String toString() {
        return englishName.isEmpty() ? name : englishName;
    }
}
