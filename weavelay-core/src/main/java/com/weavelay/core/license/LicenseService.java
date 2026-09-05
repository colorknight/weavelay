package com.weavelay.core.license;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * 本机 Licence 校验入口。
 */
public final class LicenseService {

    private final LicenseStore store;
    private final LicenseVerifier verifier;
    private final String machineId;
    private final String requestCode;
    private volatile LicenseStatus cached;

    public LicenseService() {
        this(new LicenseStore(), LicenseVerifier.embedded(), MachineFingerprint.currentMachineId());
    }

    public LicenseService(LicenseStore store, LicenseVerifier verifier, String machineId) {
        this.store = store;
        this.verifier = verifier;
        this.machineId = machineId == null ? "" : machineId;
        this.requestCode = MachineFingerprint.requestCode(this.machineId);
    }

    public String getMachineId() {
        return machineId;
    }

    public String getRequestCode() {
        return requestCode;
    }

    public LicenseStore getStore() {
        return store;
    }

    public synchronized LicenseStatus refresh() {
        cached = evaluate();
        return cached;
    }

    public LicenseStatus status() {
        LicenseStatus hit = cached;
        if (hit == null) {
            return refresh();
        }
        return hit;
    }

    public boolean allows(String feature) {
        return status().allows(feature);
    }

    public synchronized LicenseStatus importLicense(java.nio.file.Path source) throws IOException {
        store.importFrom(source);
        return refresh();
    }

    public static boolean isBypassEnabled() {
        String env = System.getenv("WEAVELAY_LICENSE_BYPASS");
        if (env != null && ("1".equals(env.trim()) || "true".equalsIgnoreCase(env.trim()))) {
            return true;
        }
        String prop = System.getProperty("weavelay.license.bypass", "");
        return "1".equals(prop.trim()) || "true".equalsIgnoreCase(prop.trim());
    }

    private LicenseStatus evaluate() {
        if (isBypassEnabled()) {
            return new LicenseStatus(
                    LicenseStatus.Kind.BYPASS,
                    "开发旁路已启用（WEAVELAY_LICENSE_BYPASS）",
                    null,
                    machineId,
                    requestCode);
        }
        LicenseDocument doc;
        try {
            doc = store.load();
        } catch (IOException ex) {
            return new LicenseStatus(
                    LicenseStatus.Kind.INVALID_SIG,
                    "无法读取 licence：" + ex.getMessage(),
                    null,
                    machineId,
                    requestCode);
        }
        if (doc == null) {
            return new LicenseStatus(
                    LicenseStatus.Kind.MISSING,
                    "未导入 licence。请将机器指纹发给厂商获取授权文件。",
                    null,
                    machineId,
                    requestCode);
        }
        if (!LicenseDocument.PRODUCT.equalsIgnoreCase(doc.getProduct())) {
            return new LicenseStatus(
                    LicenseStatus.Kind.WRONG_PRODUCT,
                    "licence 产品不匹配",
                    doc,
                    machineId,
                    requestCode);
        }
        if (!verifier.verifySignature(doc)) {
            return new LicenseStatus(
                    LicenseStatus.Kind.INVALID_SIG,
                    "licence 签名无效（文件被篡改或密钥不匹配）",
                    doc,
                    machineId,
                    requestCode);
        }
        if (!machineId.equalsIgnoreCase(doc.getMachineId().trim())) {
            return new LicenseStatus(
                    LicenseStatus.Kind.MACHINE_MISMATCH,
                    "licence 与本机不匹配（按机授权，不可复制到其他电脑）",
                    doc,
                    machineId,
                    requestCode);
        }
        if (doc.isExpired(Instant.now())) {
            String until = doc.expiresLocalDate();
            return new LicenseStatus(
                    LicenseStatus.Kind.EXPIRED,
                    "licence 已过期" + (until.isBlank() ? "" : ("：" + until)),
                    doc,
                    machineId,
                    requestCode);
        }
        String who = doc.getCustomer().isBlank() ? "" : (" · " + doc.getCustomer());
        String term = doc.isPerpetual()
                ? " · 永久"
                : (" · 有效至 " + doc.expiresLocalDate());
        return new LicenseStatus(
                LicenseStatus.Kind.VALID,
                "已授权" + who + term,
                doc,
                machineId,
                requestCode);
    }

    public static String featureLabel(String feature) {
        String f = LicenseFeatures.normalize(feature);
        return switch (f) {
            case LicenseFeatures.CONFIRM -> "确认写入 JSON";
            case LicenseFeatures.EXCEL_EXPORT -> "写出 Excel";
            case LicenseFeatures.TEMPLATE_PACK -> "模板包导出/导入";
            default -> f;
        };
    }

    public String denyMessage(String feature) {
        LicenseStatus st = status();
        String feat = featureLabel(feature);
        if (st.getKind() == LicenseStatus.Kind.MISSING) {
            return "未授权，无法" + feat + "。请到设置 → Licence 复制机器指纹。";
        }
        if (st.getKind() == LicenseStatus.Kind.EXPIRED) {
            return "授权已过期，无法" + feat;
        }
        if (st.getKind() == LicenseStatus.Kind.MACHINE_MISMATCH) {
            return "授权与本机不匹配，无法" + feat + "。请用本机指纹重新签发。";
        }
        if (!st.allows(feature)) {
            return "当前 licence 未包含「" + feat + "」权限。";
        }
        return st.getMessage().isBlank()
                ? ("无法" + feat + "：" + st.getKind().name().toLowerCase(Locale.ROOT))
                : st.getMessage();
    }
}
