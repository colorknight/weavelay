package com.weavelay.tools;

import com.weavelay.core.license.LicenseCrypto;
import com.weavelay.core.license.LicenseDocument;
import com.weavelay.core.license.LicenseFeatures;
import com.weavelay.core.license.LicenseIssuer;
import com.weavelay.core.license.LicensePublicKeys;
import com.weavelay.core.license.MachineFingerprint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 厂商侧 CLI：
 * <pre>
 *   keygen [--dir keys]
 *   issue --machine &lt;id&gt; --out file.weavelaylic [--customer X] [--dealer Y]
 *        [--days N | --until YYYY-MM-DD | --perpetual]
 *   默认 {@code --days 365}。
 * </pre>
 * 私钥：环境变量 {@code WEAVELAY_LICENSE_PRIVATE_KEY_FILE} 或 {@code keys/private.ed25519.b64}。
 */
public final class LicIssueCli {

    static final int DEFAULT_VALIDITY_DAYS = 365;

    private LicIssueCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            System.exit(2);
        }
        String cmd = args[0].toLowerCase(Locale.ROOT);
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            rest.add(args[i]);
        }
        switch (cmd) {
            case "keygen" -> keygen(rest);
            case "issue" -> issue(rest);
            case "pubkey" -> System.out.println(LicensePublicKeys.embeddedPublicKeyBase64());
            case "help", "-h", "--help" -> printHelp();
            default -> {
                System.err.println("unknown command: " + cmd);
                printHelp();
                System.exit(2);
            }
        }
    }

    private static void printHelp() {
        System.out.println("""
                WeaveLay licence issuer (offline, per-machine)

                Commands:
                  keygen [--dir keys]
                      Generate Ed25519 keypair. Prints public key to paste into LicensePublicKeys.
                      Writes private.ed25519.b64 (keep secret; never ship to dealers).

                  issue --machine <64-hex-machineId> --out <file.weavelaylic>
                        [--customer name] [--dealer id]
                        [--days N | --until YYYY-MM-DD] [--ledger path]
                      Sign a licence bound to one machine.
                      --days N          valid for N days from now (default 365)
                      --until YYYY-MM-DD  valid through that local calendar day
                      --perpetual       no expiry (explicit; not the default)

                  pubkey
                      Print embedded public key (client).

                Env:
                  WEAVELAY_LICENSE_PRIVATE_KEY_FILE  path to private.ed25519.b64
                """);
    }

    private static void keygen(List<String> args) throws Exception {
        Path dir = Path.of("keys");
        for (int i = 0; i < args.size(); i++) {
            if ("--dir".equals(args.get(i)) && i + 1 < args.size()) {
                dir = Path.of(args.get(++i));
            }
        }
        Files.createDirectories(dir);
        KeyPair kp = LicenseCrypto.generateKeyPair();
        Path pub = dir.resolve("public.ed25519.b64");
        Path priv = dir.resolve("private.ed25519.b64");
        Files.writeString(pub, LicenseCrypto.encodePublicKey(kp.getPublic()) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(priv, LicenseCrypto.encodePrivateKey(kp.getPrivate()) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("Wrote " + pub.toAbsolutePath());
        System.out.println("Wrote " + priv.toAbsolutePath());
        System.out.println();
        System.out.println("Embed this public key in LicensePublicKeys.EMBEDDED_PUBLIC_KEY_BASE64:");
        System.out.println(LicenseCrypto.encodePublicKey(kp.getPublic()));
        System.out.println();
        System.out.println("WARNING: private key must NOT go to dealers or the app installer.");
    }

    private static void issue(List<String> args) throws Exception {
        String machine = null;
        String customer = "";
        String dealer = "";
        String out = null;
        Integer days = null;
        String until = null;
        boolean perpetual = false;
        Path ledgerPath = defaultLedgerPath();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ("--machine".equals(a) && i + 1 < args.size()) {
                machine = args.get(++i);
            } else if ("--customer".equals(a) && i + 1 < args.size()) {
                customer = args.get(++i);
            } else if ("--dealer".equals(a) && i + 1 < args.size()) {
                dealer = args.get(++i);
            } else if ("--out".equals(a) && i + 1 < args.size()) {
                out = args.get(++i);
            } else if ("--days".equals(a) && i + 1 < args.size()) {
                days = Integer.parseInt(args.get(++i));
            } else if ("--until".equals(a) && i + 1 < args.size()) {
                until = args.get(++i);
            } else if ("--perpetual".equals(a)) {
                perpetual = true;
            } else if ("--ledger".equals(a) && i + 1 < args.size()) {
                ledgerPath = Path.of(args.get(++i));
            }
        }
        if (machine == null || machine.isBlank() || out == null || out.isBlank()) {
            System.err.println("--machine and --out are required");
            System.exit(2);
        }
        String machineId = MachineFingerprint.normalizeMachineIdInput(machine);
        if (machineId.length() != 64 || !machineId.matches("[0-9a-f]{64}")) {
            System.err.println("machineId must be full 64-char hex fingerprint (not request code alone).");
            System.err.println("Ask the customer to copy「机器指纹」from Settings → Licence.");
            System.exit(2);
        }

        Path privFile = resolvePrivateKeyFile();
        if (!Files.isRegularFile(privFile)) {
            System.err.println("Private key not found: " + privFile.toAbsolutePath());
            System.err.println("Run: keygen   or set WEAVELAY_LICENSE_PRIVATE_KEY_FILE");
            System.exit(2);
        }
        String privB64 = Files.readString(privFile, StandardCharsets.UTF_8).trim();
        LicenseIssuer issuer = new LicenseIssuer(privB64);
        Instant expires = parseExpiry(days, until, perpetual);
        Set<String> features = new LinkedHashSet<>(LicenseFeatures.ALL);
        LicenseDocument doc = issuer.issue(machineId, customer, dealer, expires, features);

        Path outPath = Path.of(out);
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        Files.writeString(outPath, doc.toPrettyJson(), StandardCharsets.UTF_8);
        System.out.println("Issued " + outPath.toAbsolutePath());
        System.out.println("licenseId=" + doc.getLicenseId());
        System.out.println("machineId=" + doc.getMachineId());
        System.out.println("requestCode=" + MachineFingerprint.requestCode(doc.getMachineId()));
        if (doc.isPerpetual()) {
            System.out.println("expires=perpetual");
        } else {
            System.out.println("expires=" + doc.expiresLocalDate());
        }

        try (LicenseLedger ledger = new LicenseLedger(ledgerPath)) {
            int prior = ledger.countByMachine(machineId);
            if (prior > 0) {
                System.out.println("NOTE: this machine already has " + prior + " prior issue(s) in ledger.");
            }
            ledger.record(
                    doc.getLicenseId(),
                    doc.getMachineId(),
                    doc.getCustomer(),
                    doc.getDealerId(),
                    doc.getIssuedAt(),
                    doc.getExpiresAt(),
                    String.join(",", doc.getFeatures()),
                    outPath.toAbsolutePath().toString());
            System.out.println("Ledger: " + ledgerPath.toAbsolutePath());
            if (!dealer.isBlank()) {
                System.out.println("Dealer " + dealer + " total issues: " + ledger.countByDealer(dealer));
            }
        }
    }

    static Instant parseExpiry(Integer days, String until) {
        return parseExpiry(days, until, false);
    }

    static Instant parseExpiry(Integer days, String until, boolean perpetual) {
        boolean hasUntil = until != null && !until.isBlank();
        if (perpetual) {
            if (days != null || hasUntil) {
                System.err.println("--perpetual cannot be combined with --days or --until");
                System.exit(2);
            }
            return null;
        }
        if (days != null && hasUntil) {
            System.err.println("use either --days or --until, not both");
            System.exit(2);
        }
        if (hasUntil) {
            LocalDate date;
            try {
                date = LocalDate.parse(until.trim());
            } catch (DateTimeParseException ex) {
                System.err.println("--until must be YYYY-MM-DD, got: " + until);
                System.exit(2);
                return null;
            }
            return date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant();
        }
        int n = days == null ? DEFAULT_VALIDITY_DAYS : days;
        if (n <= 0) {
            System.err.println("--days must be a positive integer (use --perpetual for no expiry)");
            System.exit(2);
        }
        return Instant.now().plus(n, ChronoUnit.DAYS);
    }

    private static Path resolvePrivateKeyFile() {
        String env = System.getenv("WEAVELAY_LICENSE_PRIVATE_KEY_FILE");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        return Path.of("keys", "private.ed25519.b64");
    }

    private static Path defaultLedgerPath() {
        String home = System.getenv("WEAVELAY_VENDOR_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home.trim(), "license-ledger.db");
        }
        return Path.of(System.getProperty("user.home", "."), ".weavelay-vendor", "license-ledger.db");
    }
}
