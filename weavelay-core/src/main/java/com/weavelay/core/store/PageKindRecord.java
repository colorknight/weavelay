package com.weavelay.core.store;

public final class PageKindRecord {

    private final String code;
    private final String displayName;
    private final String description;
    private final String familyCode;
    private final int classifyPriority;

    public PageKindRecord(String code, String displayName, String familyCode, int classifyPriority) {
        this(code, displayName, "", familyCode, classifyPriority);
    }

    public PageKindRecord(
            String code,
            String displayName,
            String description,
            String familyCode,
            int classifyPriority) {
        this.code = code;
        this.displayName = displayName;
        this.description = description == null ? "" : description;
        this.familyCode = familyCode;
        this.classifyPriority = classifyPriority;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getFamilyCode() {
        return familyCode;
    }

    public int getClassifyPriority() {
        return classifyPriority;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
