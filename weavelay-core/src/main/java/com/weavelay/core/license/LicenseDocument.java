package com.weavelay.core.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Licence 文档（JSON + Ed25519 sig）。
 * <p>
 * 签名对象：除 {@code sig} 外全部字段的 canonical JSON（对象键排序、features 排序）。
 */
public final class LicenseDocument {

    public static final String PRODUCT = "weavelay";
    public static final String FILE_EXTENSION = ".weavelaylic";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final String licenseId;
    private final String product;
    private final String machineId;
    private final String customer;
    private final String dealerId;
    private final String issuedAt;
    private final String expiresAt;
    private final Set<String> features;
    private final String sig;

    public LicenseDocument(
            String licenseId,
            String product,
            String machineId,
            String customer,
            String dealerId,
            String issuedAt,
            String expiresAt,
            Set<String> features,
            String sig) {
        this.licenseId = nullToEmpty(licenseId);
        this.product = nullToEmpty(product);
        this.machineId = nullToEmpty(machineId);
        this.customer = nullToEmpty(customer);
        this.dealerId = nullToEmpty(dealerId);
        this.issuedAt = nullToEmpty(issuedAt);
        this.expiresAt = nullToEmpty(expiresAt);
        this.features = canonicalizeFeatures(features);
        this.sig = nullToEmpty(sig);
    }

    public String getLicenseId() {
        return licenseId;
    }

    public String getProduct() {
        return product;
    }

    public String getMachineId() {
        return machineId;
    }

    public String getCustomer() {
        return customer;
    }

    public String getDealerId() {
        return dealerId;
    }

    public String getIssuedAt() {
        return issuedAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public boolean isPerpetual() {
        return expiresAt.isBlank();
    }

    /** 本机时区到期日 {@code yyyy-MM-dd}；永久返回空串。 */
    public String expiresLocalDate() {
        Instant exp = expiresInstantOrNull();
        if (exp == null) {
            return "";
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(exp);
    }

    Instant expiresInstantOrNull() {
        if (isPerpetual()) {
            return null;
        }
        try {
            return Instant.parse(expiresAt);
        } catch (Exception ex) {
            return null;
        }
    }

    public Set<String> getFeatures() {
        return features;
    }

    public String getSig() {
        return sig;
    }

    public boolean hasFeature(String feature) {
        return features.contains(LicenseFeatures.normalize(feature));
    }

    public boolean isExpired(Instant now) {
        if (expiresAt.isBlank()) {
            return false;
        }
        try {
            Instant exp = Instant.parse(expiresAt);
            return now != null && now.isAfter(exp);
        } catch (Exception ex) {
            return true;
        }
    }

    public LicenseDocument withSignature(String signature) {
        return new LicenseDocument(
                licenseId, product, machineId, customer, dealerId,
                issuedAt, expiresAt, features, signature);
    }

    /** 用于签名/验签的 canonical UTF-8 JSON（不含 sig）。 */
    public byte[] canonicalPayloadBytes() {
        try {
            ObjectNode node = toPayloadNode();
            return LicenseCrypto.utf8(MAPPER.writeValueAsString(sortNode(node)));
        } catch (IOException ex) {
            throw new IllegalStateException("canonical json failed", ex);
        }
    }

    public String toPrettyJson() {
        try {
            ObjectNode root = toPayloadNode();
            if (!sig.isBlank()) {
                root.put("sig", sig);
            }
            return MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sortNode(root));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static LicenseDocument parse(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IOException("license root must be object");
        }
        Set<String> features = new LinkedHashSet<>();
        JsonNode feat = root.get("features");
        if (feat != null && feat.isArray()) {
            for (JsonNode n : feat) {
                String f = LicenseFeatures.normalize(n.asText());
                if (!f.isEmpty()) {
                    features.add(f);
                }
            }
        }
        return new LicenseDocument(
                text(root, "licenseId"),
                text(root, "product"),
                text(root, "machineId"),
                text(root, "customer"),
                text(root, "dealerId"),
                text(root, "issuedAt"),
                text(root, "expiresAt"),
                features,
                text(root, "sig"));
    }

    private ObjectNode toPayloadNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("licenseId", licenseId);
        node.put("product", product);
        node.put("machineId", machineId);
        node.put("customer", customer);
        node.put("dealerId", dealerId);
        node.put("issuedAt", issuedAt);
        node.put("expiresAt", expiresAt);
        ArrayNode arr = node.putArray("features");
        for (String f : features) {
            arr.add(f);
        }
        return node;
    }

    private static JsonNode sortNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                sorted.put(name, sortNode(node.get(name)));
            }
            ObjectNode out = MAPPER.createObjectNode();
            for (var e : sorted.entrySet()) {
                out.set(e.getKey(), e.getValue());
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(sortNode(child));
            }
            return out;
        }
        return node;
    }

    private static Set<String> canonicalizeFeatures(Set<String> features) {
        if (features == null || features.isEmpty()) {
            return Collections.emptySet();
        }
        List<String> list = new ArrayList<>();
        for (String f : features) {
            String n = LicenseFeatures.normalize(f);
            if (!n.isEmpty()) {
                list.add(n);
            }
        }
        Collections.sort(list);
        return Collections.unmodifiableSet(new LinkedHashSet<>(list));
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LicenseDocument that)) {
            return false;
        }
        return Objects.equals(licenseId, that.licenseId)
                && Objects.equals(machineId, that.machineId)
                && Objects.equals(sig, that.sig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(licenseId, machineId, sig);
    }
}
