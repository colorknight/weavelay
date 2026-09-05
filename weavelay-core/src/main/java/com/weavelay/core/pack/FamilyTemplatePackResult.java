package com.weavelay.core.pack;

/**
 * 导入结果摘要。
 */
public final class FamilyTemplatePackResult {

    private final String familyCode;
    private final String familyName;
    private final int pageKindCount;
    private final boolean excelIncluded;
    private final String excelPath;
    private final boolean overwritten;

    public FamilyTemplatePackResult(
            String familyCode,
            String familyName,
            int pageKindCount,
            boolean excelIncluded,
            String excelPath,
            boolean overwritten) {
        this.familyCode = familyCode == null ? "" : familyCode;
        this.familyName = familyName == null ? "" : familyName;
        this.pageKindCount = pageKindCount;
        this.excelIncluded = excelIncluded;
        this.excelPath = excelPath == null ? "" : excelPath;
        this.overwritten = overwritten;
    }

    public String getFamilyCode() {
        return familyCode;
    }

    public String getFamilyName() {
        return familyName;
    }

    public int getPageKindCount() {
        return pageKindCount;
    }

    public boolean isExcelIncluded() {
        return excelIncluded;
    }

    public String getExcelPath() {
        return excelPath;
    }

    public boolean isOverwritten() {
        return overwritten;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(familyName.isEmpty() ? familyCode : familyName);
        sb.append("（").append(familyCode).append("）");
        sb.append("：").append(pageKindCount).append(" 个页面类型");
        if (excelIncluded) {
            sb.append("，已安装 Excel 模板");
        } else {
            sb.append("，无 Excel 模板");
        }
        if (overwritten) {
            sb.append("（已覆盖同代码）");
        }
        return sb.toString();
    }
}
