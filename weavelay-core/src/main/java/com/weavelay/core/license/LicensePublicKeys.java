package com.weavelay.core.license;

import java.security.PublicKey;

/**
 * 客户端嵌入的验签公钥（对应厂商私钥；私钥不进安装包）。
 */
public final class LicensePublicKeys {

    /**
     * Ed25519 X509 Base64。更换密钥时：用 {@code lic-issue keygen} 生成后替换此常量，并作废旧 licence。
     */
    static final String EMBEDDED_PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEATfZiwlk/fOSZqkSVGtDXttu1/DSwDAsBjRn8boe1G5o=";

    private LicensePublicKeys() {}

    public static PublicKey embeddedPublicKey() {
        return LicenseCrypto.decodePublicKey(EMBEDDED_PUBLIC_KEY_BASE64);
    }

    public static String embeddedPublicKeyBase64() {
        return EMBEDDED_PUBLIC_KEY_BASE64;
    }
}
