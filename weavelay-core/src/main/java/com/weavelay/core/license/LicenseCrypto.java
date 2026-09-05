package com.weavelay.core.license;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 签名 / 验签；密钥以 Base64（标准）存 PKCS8 / X509 编码。
 */
public final class LicenseCrypto {

    public static final String ALGORITHM = "Ed25519";

    private LicenseCrypto() {}

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Ed25519 unavailable", ex);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(raw));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid Ed25519 public key", ex);
        }
    }

    public static PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(raw));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid Ed25519 private key", ex);
        }
    }

    public static String signBase64(PrivateKey privateKey, byte[] data) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException ex) {
            throw new IllegalStateException("sign failed", ex);
        }
    }

    public static boolean verifyBase64(PublicKey publicKey, byte[] data, String sigBase64) {
        if (sigBase64 == null || sigBase64.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(Base64.getDecoder().decode(sigBase64.trim()));
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException
                 | IllegalArgumentException ex) {
            return false;
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
