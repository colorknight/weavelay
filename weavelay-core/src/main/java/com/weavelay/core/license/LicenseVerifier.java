package com.weavelay.core.license;

import java.security.PublicKey;

/**
 * 使用嵌入或注入的公钥验签。
 */
public final class LicenseVerifier {

    private final PublicKey publicKey;

    public LicenseVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    public LicenseVerifier(String publicKeyBase64) {
        this(LicenseCrypto.decodePublicKey(publicKeyBase64));
    }

    public static LicenseVerifier embedded() {
        return new LicenseVerifier(LicensePublicKeys.embeddedPublicKey());
    }

    public boolean verifySignature(LicenseDocument doc) {
        if (doc == null || doc.getSig().isBlank()) {
            return false;
        }
        return LicenseCrypto.verifyBase64(publicKey, doc.canonicalPayloadBytes(), doc.getSig());
    }
}
