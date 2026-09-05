package com.weavelay.core.license;

/**
 * 本机 Licence 状态摘要。
 */
public final class LicenseStatus {

    public enum Kind {
        VALID,
        MISSING,
        INVALID_SIG,
        MACHINE_MISMATCH,
        EXPIRED,
        WRONG_PRODUCT,
        BYPASS
    }

    private final Kind kind;
    private final String message;
    private final LicenseDocument document;
    private final String machineId;
    private final String requestCode;

    public LicenseStatus(
            Kind kind,
            String message,
            LicenseDocument document,
            String machineId,
            String requestCode) {
        this.kind = kind;
        this.message = message == null ? "" : message;
        this.document = document;
        this.machineId = machineId == null ? "" : machineId;
        this.requestCode = requestCode == null ? "" : requestCode;
    }

    public Kind getKind() {
        return kind;
    }

    public String getMessage() {
        return message;
    }

    public LicenseDocument getDocument() {
        return document;
    }

    public String getMachineId() {
        return machineId;
    }

    public String getRequestCode() {
        return requestCode;
    }

    public boolean isUsable() {
        return kind == Kind.VALID || kind == Kind.BYPASS;
    }

    public boolean allows(String feature) {
        if (kind == Kind.BYPASS) {
            return true;
        }
        if (kind != Kind.VALID || document == null) {
            return false;
        }
        return document.hasFeature(feature);
    }
}
