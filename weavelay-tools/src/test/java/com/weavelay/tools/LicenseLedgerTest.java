package com.weavelay.tools;

import com.weavelay.core.license.LicenseCrypto;
import com.weavelay.core.license.LicenseDocument;
import com.weavelay.core.license.LicenseFeatures;
import com.weavelay.core.license.LicenseIssuer;
import com.weavelay.core.license.LicenseVerifier;
import com.weavelay.core.license.MachineFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseLedgerTest {

    @TempDir
    Path temp;

    @Test
    void recordsIssues() throws Exception {
        Path db = temp.resolve("ledger.db");
        try (LicenseLedger ledger = new LicenseLedger(db)) {
            ledger.record("id-1", "machine-a", "cust", "dealer-1",
                    "t0", "", "confirm,excel_export", "out.lic");
            ledger.record("id-2", "machine-a", "cust", "dealer-1",
                    "t1", "", "confirm", "out2.lic");
            assertEquals(2, ledger.countByMachine("machine-a"));
            assertEquals(2, ledger.countByDealer("dealer-1"));
        }
    }

    @Test
    void issueWithEphemeralKeyVerifies() throws Exception {
        KeyPair kp = LicenseCrypto.generateKeyPair();
        String machine = MachineFingerprint.machineIdFromParts(List.of("cli-test"));
        LicenseDocument doc = new LicenseIssuer(kp.getPrivate()).issue(
                machine, "c", "d", null, LicenseFeatures.ALL);
        Path out = temp.resolve("t.weavelaylic");
        Files.writeString(out, doc.toPrettyJson());
        LicenseDocument loaded = LicenseDocument.parse(Files.readString(out));
        assertTrue(new LicenseVerifier(kp.getPublic()).verifySignature(loaded));
    }

    @Test
    void parseUntilIsEndOfLocalDay() {
        Instant exp = LicIssueCli.parseExpiry(null, "2027-03-31");
        Instant expected = LocalDate.parse("2027-03-31")
                .atTime(LocalTime.MAX)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        assertEquals(expected, exp);
    }

    @Test
    void omitDaysAndUntilDefaultsTo365Days() {
        Instant exp = LicIssueCli.parseExpiry(null, null);
        Instant lo = Instant.now().plus(364, java.time.temporal.ChronoUnit.DAYS);
        Instant hi = Instant.now().plus(366, java.time.temporal.ChronoUnit.DAYS);
        assertTrue(exp.isAfter(lo) && exp.isBefore(hi));
        Instant blankUntil = LicIssueCli.parseExpiry(null, "  ");
        assertTrue(blankUntil.isAfter(lo) && blankUntil.isBefore(hi));
    }

    @Test
    void perpetualFlagHasNoExpiry() {
        assertNull(LicIssueCli.parseExpiry(null, null, true));
    }
}
