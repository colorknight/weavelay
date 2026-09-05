package com.weavelay.core.pack;

/**
 * 模板包导出/导入业务错误（非法包、冲突等）。
 */
public final class FamilyTemplatePackException extends Exception {

    public enum Reason {
        INVALID_PACK,
        FAMILY_EXISTS,
        PAGE_KIND_CONFLICT,
        FAMILY_NOT_FOUND,
        IO
    }

    private final Reason reason;

    public FamilyTemplatePackException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FamilyTemplatePackException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
