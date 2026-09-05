package com.weavelay.core.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedPublicKeyMatchTest {

    static boolean privateKeyPresent() {
        return Files.isRegularFile(Path.of("keys", "private.ed25519.b64"))
                || Files.isRegularFile(Path.of("..", "weavelay-tools", "keys", "private.ed25519.b64"));
    }

    @Test
    @EnabledIf("privateKeyPresent")
    void embeddedPublicVerifiesIssuerPrivate() throws Exception {
        Path privPath = Files.isRegularFile(Path.of("keys", "private.ed25519.b64"))
                ? Path.of("keys", "private.ed25519.b64")
                : Path.of("..", "weavelay-tools", "keys", "private.ed25519.b64");
        String priv = Files.readString(privPath).trim();
        String machine = MachineFingerprint.machineIdFromParts(List.of("embed-match"));
        LicenseDocument doc = new LicenseIssuer(priv).issue(
                machine, "dev", "self", null, LicenseFeatures.ALL);
        assertTrue(LicenseVerifier.embedded().verifySignature(doc),
                "embedded public key must match weavelay-tools/keys/private.ed25519.b64");
    }
}
