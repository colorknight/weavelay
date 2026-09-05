package com.weavelay.core.license;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 本机指纹与对外申请码。
 * <p>
 * {@code machineId}：SHA-256 十六进制（加盐）。申请码为可读短码，由 machineId 派生。
 */
public final class MachineFingerprint {

    /** 应用盐；勿与签发私钥混淆。 */
    private static final String SALT = "weavelay-machine-v1";

    private MachineFingerprint() {}

    public static String currentMachineId() {
        return machineIdFromParts(collectParts());
    }

    /** 由 machineId 生成分组申请码，如 {@code ABCD-EFGH-IJKL-MNOP}。 */
    public static String requestCode(String machineId) {
        if (machineId == null || machineId.isBlank()) {
            return "";
        }
        String hex = machineId.trim().toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
        if (hex.length() < 16) {
            hex = (hex + "0000000000000000").substring(0, 16);
        }
        // 用十六进制直接分组（稳定、可从完整 machineId 核对）
        String a = hex.substring(0, 4);
        String b = hex.substring(4, 8);
        String c = hex.substring(8, 12);
        String d = hex.substring(12, 16);
        return a + "-" + b + "-" + c + "-" + d;
    }

    public static String currentRequestCode() {
        return requestCode(currentMachineId());
    }

    /**
     * 解析申请码或完整 machineId。申请码不足以还原完整 id，签发应优先用完整 machineId；
     * 若只收到申请码，返回规范化申请码字符串并在台账中标注（issuer 应要求完整 machineId）。
     */
    public static String normalizeMachineIdInput(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return "";
        }
        String hex = t.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
        if (hex.length() == 64) {
            return hex;
        }
        return t.toUpperCase(Locale.ROOT).replace(' ', '-');
    }

    public static String machineIdFromParts(List<String> parts) {
        StringBuilder sb = new StringBuilder(SALT);
        for (String p : parts) {
            sb.append('|').append(p == null ? "" : p.trim());
        }
        return sha256Hex(sb.toString());
    }

    static List<String> collectParts() {
        List<String> parts = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            parts.add(runCmd("wmic", "csproduct", "get", "UUID"));
            parts.add(runCmd("wmic", "baseboard", "get", "SerialNumber"));
            parts.add(firstDiskSerial());
        } else {
            parts.add(runCmd("sh", "-c", "cat /etc/machine-id 2>/dev/null || true"));
            parts.add(System.getProperty("user.name", ""));
        }
        parts.add(System.getProperty("os.name", ""));
        parts.add(System.getProperty("os.arch", ""));
        // 过滤空
        List<String> cleaned = new ArrayList<>();
        for (String p : parts) {
            String c = cleanWmic(p);
            if (!c.isBlank()) {
                cleaned.add(c);
            }
        }
        if (cleaned.isEmpty()) {
            cleaned.add("fallback-" + System.getProperty("user.home", "unknown"));
        }
        return cleaned;
    }

    private static String firstDiskSerial() {
        String raw = runCmd("wmic", "diskdrive", "get", "SerialNumber");
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.equalsIgnoreCase("SerialNumber")) {
                continue;
            }
            return t;
        }
        return "";
    }

    private static String cleanWmic(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.equals("uuid") || lower.equals("serialnumber") || lower.equals("serial number")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }

    private static String runCmd(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "";
            }
            byte[] out = p.getInputStream().readAllBytes();
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
