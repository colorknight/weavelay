package com.weavelay.core.license;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 签发 / 验签。
 */
public final class LicenseIssuer {

    private final PrivateKey privateKey;

    public LicenseIssuer(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public LicenseIssuer(String privateKeyBase64) {
        this(LicenseCrypto.decodePrivateKey(privateKeyBase64));
    }

    public LicenseDocument issue(
            String machineId,
            String customer,
            String dealerId,
            Instant expiresAt,
            Set<String> features) {
        Instant now = Instant.now();
        Set<String> feats = features == null || features.isEmpty()
                ? LicenseFeatures.ALL
                : features;
        LicenseDocument unsigned = new LicenseDocument(
                UUID.randomUUID().toString(),
                LicenseDocument.PRODUCT,
                machineId == null ? "" : machineId.trim(),
                customer == null ? "" : customer.trim(),
                dealerId == null ? "" : dealerId.trim(),
                now.toString(),
                expiresAt == null ? "" : expiresAt.toString(),
                feats,
                "");
        String sig = LicenseCrypto.signBase64(privateKey, unsigned.canonicalPayloadBytes());
        return unsigned.withSignature(sig);
    }
}
