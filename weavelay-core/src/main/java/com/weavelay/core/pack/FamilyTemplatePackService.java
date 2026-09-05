package com.weavelay.core.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.weavelay.core.WeavelayDataDir;
import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.PairMode;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.store.FamilyRecord;
import com.weavelay.core.store.PageKindRecord;
import com.weavelay.core.store.WeaveDatabase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 单个文件类型的模板包导出/导入（.weavelaypack = zip）。
 */
public final class FamilyTemplatePackService {

    public static final int FORMAT_VERSION = 1;
    public static final String EXTENSION = ".weavelaypack";
    public static final String EXCEL_RELATIVE = "excel/template.xlsx";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final WeaveDatabase database;
    private final Path dataRoot;

    public FamilyTemplatePackService(WeaveDatabase database) {
        this(database, WeavelayDataDir.get());
    }

    public FamilyTemplatePackService(WeaveDatabase database, Path dataRoot) {
        this.database = database;
        this.dataRoot = dataRoot == null ? WeavelayDataDir.get() : dataRoot;
    }

    /**
     * 导出指定文件类型到 {@code destPack}（通常以 .weavelaypack 结尾）。
     *
     * @return true 若包内含 Excel
     */
    public boolean exportFamily(String familyCode, Path destPack)
            throws SQLException, FamilyTemplatePackException {
        FamilyRecord family = database.findFamily(familyCode);
        if (family == null) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.FAMILY_NOT_FOUND,
                    "文件类型不存在: " + familyCode);
        }
        try {
            Path parent = destPack.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ex) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.IO, "无法创建导出目录", ex);
        }

        String definition = database.getFamilyOutputDefinitionJson(familyCode);
        List<PageKindRecord> kinds = database.listAllPageKinds(familyCode);
        Path excelSrc = resolveExistingExcel(family.getExcelTemplatePath());
        boolean hasExcel = excelSrc != null;

        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("appSchemaVersion", database.getSchemaVersion());
        manifest.put("familyCode", family.getCode());
        manifest.put("name", family.getName());
        manifest.put("englishName", family.getEnglishName());
        manifest.put("description", family.getDescription());
        manifest.put("excelRelativePath", hasExcel ? EXCEL_RELATIVE : "");
        manifest.put("exportedAt", Instant.now().toString());

        try (OutputStream fos = Files.newOutputStream(destPack);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            putJson(zip, "manifest.json", manifest);
            putBytes(zip, "definition.json",
                    (definition == null || definition.isBlank() ? "{}" : definition)
                            .getBytes(StandardCharsets.UTF_8));

            for (PageKindRecord kind : kinds) {
                String base = "page_kinds/" + kind.getCode() + "/";
                ObjectNode meta = MAPPER.createObjectNode();
                meta.put("code", kind.getCode());
                meta.put("displayName", kind.getDisplayName());
                meta.put("description", kind.getDescription());
                meta.put("classifyPriority", kind.getClassifyPriority());
                putJson(zip, base + "meta.json", meta);

                PageRuleConfig rule = database.loadPageRule(kind.getCode());
                ObjectNode ruleJson = MAPPER.createObjectNode();
                ruleJson.put("yThreshold", rule.getYThreshold());
                ruleJson.put("xThreshold", rule.getXThreshold());
                ruleJson.put("mergeSep", rule.getMergeSep());
                ruleJson.put("pairMode", rule.getPairMode().name());
                ruleJson.put("rowTolerance", rule.getRowTolerance());
                ruleJson.put("colTolerance", rule.getColTolerance());
                putJson(zip, base + "page_rule.json", ruleJson);

                ArrayNode slots = MAPPER.createArrayNode();
                for (SlotDefinition slot : database.listSlots(kind.getCode())) {
                    ObjectNode s = MAPPER.createObjectNode();
                    s.put("slotCode", slot.getSlotCode());
                    s.put("slotLabel", slot.getSlotLabel());
                    s.put("sortOrder", slot.getSortOrder());
                    s.put("regionX", slot.getRegionX());
                    s.put("regionY", slot.getRegionY());
                    s.put("regionW", slot.getRegionW());
                    s.put("regionH", slot.getRegionH());
                    s.put("fieldType", slot.getFieldType());
                    s.put("fieldMeta", slot.getFieldMeta() == null ? "" : slot.getFieldMeta());
                    slots.add(s);
                }
                putJson(zip, base + "slots.json", slots);
            }

            if (hasExcel) {
                zip.putNextEntry(new ZipEntry(EXCEL_RELATIVE));
                Files.copy(excelSrc, zip);
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.IO, "写出模板包失败", ex);
        }
        return hasExcel;
    }

    /** 读取包内 manifest（不写库）。 */
    public PackManifest peekManifest(Path packPath) throws FamilyTemplatePackException {
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            return readManifest(zip);
        } catch (FamilyTemplatePackException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.IO, "无法打开模板包", ex);
        }
    }

    public FamilyTemplatePackResult importPack(Path packPath, boolean overwrite)
            throws SQLException, FamilyTemplatePackException {
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            PackManifest manifest = readManifest(zip);
            boolean existed = database.familyExists(manifest.familyCode);
            if (existed && !overwrite) {
                throw new FamilyTemplatePackException(
                        FamilyTemplatePackException.Reason.FAMILY_EXISTS,
                        "本地已有文件类型「" + manifest.familyCode + "」，需确认覆盖");
            }

            List<ImportedPageKind> kinds = loadPageKindsFromZip(zip);
            for (ImportedPageKind kind : kinds) {
                String owner = database.findPageKindFamilyCode(kind.code);
                if (owner != null && !owner.equals(manifest.familyCode)) {
                    throw new FamilyTemplatePackException(
                            FamilyTemplatePackException.Reason.PAGE_KIND_CONFLICT,
                            "页面类型代码「" + kind.code + "」已被文件类型「" + owner + "」占用");
                }
            }

            if (existed) {
                database.deleteDocumentFamily(manifest.familyCode);
            }

            database.addDocumentFamily(
                    manifest.familyCode,
                    manifest.name,
                    manifest.englishName,
                    manifest.description);

            String definition = readZipText(zip, "definition.json");
            if (definition != null && !definition.isBlank()) {
                database.saveFamilyOutputDefinitionJson(manifest.familyCode, definition.trim());
            }

            for (ImportedPageKind kind : kinds) {
                database.insertImportedPageKind(
                        manifest.familyCode,
                        kind.code,
                        kind.displayName,
                        kind.description,
                        kind.classifyPriority,
                        kind.rule,
                        kind.slots);
            }

            boolean excelIncluded = false;
            String excelInstalled = "";
            String excelRel = manifest.excelRelativePath;
            if (excelRel != null && !excelRel.isBlank()) {
                ZipEntry excelEntry = zip.getEntry(excelRel.replace('\\', '/'));
                if (excelEntry != null) {
                    Path dest = dataRoot.resolve("templates")
                            .resolve(manifest.familyCode)
                            .resolve("template.xlsx");
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zip.getInputStream(excelEntry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    excelInstalled = dest.toAbsolutePath().normalize().toString();
                    database.updateDocumentFamilyExcelTemplate(manifest.familyCode, excelInstalled);
                    excelIncluded = true;
                }
            }

            return new FamilyTemplatePackResult(
                    manifest.familyCode,
                    manifest.name,
                    kinds.size(),
                    excelIncluded,
                    excelInstalled,
                    existed);
        } catch (FamilyTemplatePackException | SQLException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.IO, "导入模板包失败", ex);
        }
    }

    private static Path resolveExistingExcel(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path p = Path.of(path.trim());
        return Files.isRegularFile(p) ? p : null;
    }

    private static void putJson(ZipOutputStream zip, String name, JsonNode node) throws IOException {
        putBytes(zip, name, MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private PackManifest readManifest(ZipFile zip) throws FamilyTemplatePackException, IOException {
        String text = readZipText(zip, "manifest.json");
        if (text == null || text.isBlank()) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.INVALID_PACK, "缺少 manifest.json");
        }
        JsonNode root = MAPPER.readTree(text);
        int format = root.path("formatVersion").asInt(0);
        if (format != FORMAT_VERSION) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.INVALID_PACK,
                    "不支持的模板包版本: " + format);
        }
        String code = textOrEmpty(root, "familyCode");
        if (code.isBlank()) {
            throw new FamilyTemplatePackException(
                    FamilyTemplatePackException.Reason.INVALID_PACK, "manifest 缺少 familyCode");
        }
        PackManifest m = new PackManifest();
        m.formatVersion = format;
        m.appSchemaVersion = root.path("appSchemaVersion").asInt(0);
        m.familyCode = code;
        m.name = textOrEmpty(root, "name");
        m.englishName = textOrEmpty(root, "englishName");
        m.description = textOrEmpty(root, "description");
        m.excelRelativePath = textOrEmpty(root, "excelRelativePath");
        m.exportedAt = textOrEmpty(root, "exportedAt");
        return m;
    }

    private List<ImportedPageKind> loadPageKindsFromZip(ZipFile zip)
            throws IOException, FamilyTemplatePackException {
        List<String> kindCodes = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName().replace('\\', '/');
            if (name.startsWith("page_kinds/") && name.endsWith("/meta.json")) {
                String mid = name.substring("page_kinds/".length(), name.length() - "/meta.json".length());
                if (!mid.isBlank() && !mid.contains("/")) {
                    kindCodes.add(mid);
                }
            }
        }
        kindCodes.sort(String::compareTo);

        List<ImportedPageKind> list = new ArrayList<>();
        for (String code : kindCodes) {
            String base = "page_kinds/" + code + "/";
            String metaText = readZipText(zip, base + "meta.json");
            if (metaText == null) {
                throw new FamilyTemplatePackException(
                        FamilyTemplatePackException.Reason.INVALID_PACK,
                        "缺少 " + base + "meta.json");
            }
            JsonNode meta = MAPPER.readTree(metaText);
            ImportedPageKind kind = new ImportedPageKind();
            kind.code = textOrEmpty(meta, "code");
            if (kind.code.isBlank()) {
                kind.code = code;
            }
            kind.displayName = textOrEmpty(meta, "displayName");
            kind.description = textOrEmpty(meta, "description");
            kind.classifyPriority = meta.path("classifyPriority").asInt(10);

            kind.rule = new PageRuleConfig(80, 80, "", PairMode.NONE, 80, 120);
            String ruleText = readZipText(zip, base + "page_rule.json");
            if (ruleText != null && !ruleText.isBlank()) {
                JsonNode r = MAPPER.readTree(ruleText);
                kind.rule = new PageRuleConfig(
                        r.path("yThreshold").asDouble(80),
                        r.path("xThreshold").asDouble(80),
                        textOrEmpty(r, "mergeSep"),
                        PairMode.fromCode(textOrEmpty(r, "pairMode")),
                        r.path("rowTolerance").asDouble(80),
                        r.path("colTolerance").asDouble(120));
            }

            kind.slots = new ArrayList<>();
            String slotsText = readZipText(zip, base + "slots.json");
            if (slotsText != null && !slotsText.isBlank()) {
                JsonNode arr = MAPPER.readTree(slotsText);
                if (arr.isArray()) {
                    for (JsonNode s : arr) {
                        kind.slots.add(new SlotDefinition(
                                textOrEmpty(s, "slotCode"),
                                textOrEmpty(s, "slotLabel"),
                                s.path("sortOrder").asInt(0),
                                s.path("regionX").asInt(0),
                                s.path("regionY").asInt(0),
                                s.path("regionW").asInt(0),
                                s.path("regionH").asInt(0),
                                textOrEmpty(s, "fieldType").isBlank()
                                        ? "text" : textOrEmpty(s, "fieldType"),
                                textOrEmpty(s, "fieldMeta")));
                    }
                }
            }
            list.add(kind);
        }
        return list;
    }

    private static String readZipText(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return "";
        }
        return n.asText("");
    }

    /** 公开给 UI 预读用。 */
    public static final class PackManifest {
        public int formatVersion;
        public int appSchemaVersion;
        public String familyCode;
        public String name;
        public String englishName;
        public String description;
        public String excelRelativePath;
        public String exportedAt;
    }

    private static final class ImportedPageKind {
        String code;
        String displayName;
        String description;
        int classifyPriority;
        PageRuleConfig rule;
        List<SlotDefinition> slots;
    }
}
