package com.weavelay.core.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseRoundTripTest {

    @TempDir
    Path temp;

    @Test
    void signVerifyAndMachineBind() throws Exception {
        KeyPair kp = LicenseCrypto.generateKeyPair();
        LicenseIssuer issuer = new LicenseIssuer(kp.getPrivate());
        String machine = MachineFingerprint.machineIdFromParts(List.of("board-1", "disk-2"));
        LicenseDocument doc = issuer.issue(
                machine, "Acme", "dealer-a", null, LicenseFeatures.ALL);

        LicenseVerifier verifier = new LicenseVerifier(kp.getPublic());
        assertTrue(verifier.verifySignature(doc));

        LicenseStore store = new LicenseStore(temp.resolve("license.weavelaylic"));
        store.save(doc);
        LicenseDocument loaded = store.load();
        assertTrue(verifier.verifySignature(loaded));

        LicenseService ok = new LicenseService(store, verifier, machine);
        assertTrue(ok.refresh().isUsable());
        assertTrue(ok.allows(LicenseFeatures.EXCEL_EXPORT));

        LicenseService other = new LicenseService(store, verifier, machine + "x");
        assertEquals(LicenseStatus.Kind.MACHINE_MISMATCH, other.refresh().getKind());
        assertFalse(other.allows(LicenseFeatures.CONFIRM));
    }

    @Test
    void expiredRejected() {
        KeyPair kp = LicenseCrypto.generateKeyPair();
        LicenseIssuer issuer = new LicenseIssuer(kp.getPrivate());
        String machine = "abc";
        LicenseDocument doc = issuer.issue(
                machine, "", "", Instant.now().minus(1, ChronoUnit.DAYS), Set.of(LicenseFeatures.CONFIRM));
        LicenseStore store = new LicenseStore(temp.resolve("exp.weavelaylic"));
        try {
            store.save(doc);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        LicenseService svc = new LicenseService(store, new LicenseVerifier(kp.getPublic()), machine);
        assertEquals(LicenseStatus.Kind.EXPIRED, svc.refresh().getKind());
    }

    @Test
    void futureExpiryStillUsable() throws Exception {
        KeyPair kp = LicenseCrypto.generateKeyPair();
        LicenseIssuer issuer = new LicenseIssuer(kp.getPrivate());
        String machine = "abc";
        LicenseDocument doc = issuer.issue(
                machine, "试产", "", Instant.now().plus(40, ChronoUnit.DAYS),
                Set.of(LicenseFeatures.CONFIRM));
        LicenseStore store = new LicenseStore(temp.resolve("ok.weavelaylic"));
        store.save(doc);
        LicenseService svc = new LicenseService(store, new LicenseVerifier(kp.getPublic()), machine);
        LicenseStatus st = svc.refresh();
        assertEquals(LicenseStatus.Kind.VALID, st.getKind());
        assertTrue(st.getMessage().contains("有效至"));
        assertFalse(doc.expiresLocalDate().isBlank());
    }

    @Test
    void requestCodeStable() {
        String id = MachineFingerprint.machineIdFromParts(List.of("x", "y"));
        String code = MachineFingerprint.requestCode(id);
        assertTrue(code.matches("[0-9A-F]{4}(-[0-9A-F]{4}){3}"));
        assertEquals(code, MachineFingerprint.requestCode(id));
    }

    @Test
    void tamperBreaksSignature() throws Exception {
        KeyPair kp = LicenseCrypto.generateKeyPair();
        LicenseDocument doc = new LicenseIssuer(kp.getPrivate()).issue(
                "m1", "c", "d", null, LicenseFeatures.ALL);
        LicenseDocument tampered = new LicenseDocument(
                doc.getLicenseId(), doc.getProduct(), "other-machine",
                doc.getCustomer(), doc.getDealerId(), doc.getIssuedAt(),
                doc.getExpiresAt(), doc.getFeatures(), doc.getSig());
        assertFalse(new LicenseVerifier(kp.getPublic()).verifySignature(tampered));
    }
}
