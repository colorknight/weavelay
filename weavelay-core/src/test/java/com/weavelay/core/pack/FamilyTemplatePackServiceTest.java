package com.weavelay.core.pack;

import com.weavelay.core.page.PageRuleConfig;
import com.weavelay.core.page.PairMode;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.store.FamilyRecord;
import com.weavelay.core.store.PageKindRecord;
import com.weavelay.core.store.WeaveDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyTemplatePackServiceTest {

    @TempDir
    Path tempDir;

    private WeaveDatabase sourceDb;
    private WeaveDatabase targetDb;
    private Path sourceData;
    private Path targetData;

    @BeforeEach
    void setUp() throws Exception {
        sourceData = tempDir.resolve("source-home");
        targetData = tempDir.resolve("target-home");
        Files.createDirectories(sourceData);
        Files.createDirectories(targetData);
        sourceDb = WeaveDatabase.open(sourceData.resolve("weavelay.db"));
        targetDb = WeaveDatabase.open(targetData.resolve("weavelay.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sourceDb != null) {
            sourceDb.close();
        }
        if (targetDb != null) {
            targetDb.close();
        }
    }

    @Test
    void roundTripWithExcelRegionsAndDefinition() throws Exception {
        Path excel = sourceData.resolve("orig.xlsx");
        Files.writeString(excel, "fake-xlsx-bytes");

        seedDemoFamily(sourceDb, excel);

        Path pack = tempDir.resolve("demo.weavelaypack");
        FamilyTemplatePackService exporter = new FamilyTemplatePackService(sourceDb, sourceData);
        assertTrue(exporter.exportFamily("demo_pack", pack));
        assertTrue(Files.isRegularFile(pack));

        FamilyTemplatePackService importer = new FamilyTemplatePackService(targetDb, targetData);
        FamilyTemplatePackResult result = importer.importPack(pack, false);
        assertEquals("demo_pack", result.getFamilyCode());
        assertEquals(1, result.getPageKindCount());
        assertTrue(result.isExcelIncluded());
        assertFalse(result.isOverwritten());

        FamilyRecord family = targetDb.findFamily("demo_pack");
        assertNotNull(family);
        assertEquals("演示规程", family.getName());
        assertEquals("Demo Spec", family.getEnglishName());
        assertTrue(Files.isRegularFile(Path.of(family.getExcelTemplatePath())));
        assertTrue(family.getExcelTemplatePath().contains("templates"));
        assertTrue(family.getExcelTemplatePath().replace('\\', '/').contains("demo_pack"));

        assertEquals("{\"group\":[],\"route\":[]}",
                targetDb.getFamilyOutputDefinitionJson("demo_pack").trim());

        List<PageKindRecord> kinds = targetDb.listAllPageKinds("demo_pack");
        assertEquals(1, kinds.size());
        assertEquals("demo_cover", kinds.get(0).getCode());
        assertEquals("演示封面", kinds.get(0).getDisplayName());
        assertEquals(15, kinds.get(0).getClassifyPriority());

        PageRuleConfig rule = targetDb.loadPageRule("demo_cover");
        assertEquals(90, rule.getYThreshold(), 0.01);
        assertEquals(PairMode.HORIZONTAL, rule.getPairMode());

        List<SlotDefinition> slots = targetDb.listSlots("demo_cover");
        assertEquals(1, slots.size());
        SlotDefinition slot = slots.get(0);
        assertEquals("part_name", slot.getSlotCode());
        assertEquals("零部件名称", slot.getSlotLabel());
        assertEquals(10, slot.getRegionX());
        assertEquals(20, slot.getRegionY());
        assertEquals(100, slot.getRegionW());
        assertEquals(40, slot.getRegionH());
        assertEquals("text", slot.getFieldType());
    }

    @Test
    void exportWithoutExcelStillImports() throws Exception {
        seedDemoFamily(sourceDb, null);
        Path pack = tempDir.resolve("no-excel.weavelaypack");
        FamilyTemplatePackService exporter = new FamilyTemplatePackService(sourceDb, sourceData);
        assertFalse(exporter.exportFamily("demo_pack", pack));

        FamilyTemplatePackService importer = new FamilyTemplatePackService(targetDb, targetData);
        FamilyTemplatePackResult result = importer.importPack(pack, false);
        assertFalse(result.isExcelIncluded());
        FamilyRecord family = targetDb.findFamily("demo_pack");
        assertNotNull(family);
        assertFalse(family.hasExcelTemplate());
    }

    @Test
    void overwriteSameFamilyCode() throws Exception {
        seedDemoFamily(sourceDb, null);
        Path pack = tempDir.resolve("overwrite.weavelaypack");
        new FamilyTemplatePackService(sourceDb, sourceData).exportFamily("demo_pack", pack);

        FamilyTemplatePackService importer = new FamilyTemplatePackService(targetDb, targetData);
        importer.importPack(pack, false);
        targetDb.saveFamilyOutputDefinitionJson("demo_pack", "{\"changed\":true}");

        FamilyTemplatePackResult second = importer.importPack(pack, true);
        assertTrue(second.isOverwritten());
        assertEquals("{\"group\":[],\"route\":[]}",
                targetDb.getFamilyOutputDefinitionJson("demo_pack").trim());
    }

    @Test
    void refusesOverwriteWithoutFlag() throws Exception {
        seedDemoFamily(sourceDb, null);
        Path pack = tempDir.resolve("conflict.weavelaypack");
        new FamilyTemplatePackService(sourceDb, sourceData).exportFamily("demo_pack", pack);

        FamilyTemplatePackService importer = new FamilyTemplatePackService(targetDb, targetData);
        importer.importPack(pack, false);

        FamilyTemplatePackException ex = assertThrows(
                FamilyTemplatePackException.class,
                () -> importer.importPack(pack, false));
        assertEquals(FamilyTemplatePackException.Reason.FAMILY_EXISTS, ex.getReason());
    }

    @Test
    void invalidPackMissingManifest() throws Exception {
        Path bad = tempDir.resolve("bad.weavelaypack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bad))) {
            zip.putNextEntry(new ZipEntry("definition.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        FamilyTemplatePackService importer = new FamilyTemplatePackService(targetDb, targetData);
        FamilyTemplatePackException ex = assertThrows(
                FamilyTemplatePackException.class,
                () -> importer.importPack(bad, false));
        assertEquals(FamilyTemplatePackException.Reason.INVALID_PACK, ex.getReason());
    }

    private static void seedDemoFamily(WeaveDatabase db, Path excel) throws Exception {
        db.addDocumentFamily("demo_pack", "演示规程", "Demo Spec", "pack test");
        db.saveFamilyOutputDefinitionJson("demo_pack", "{\"group\":[],\"route\":[]}");
        if (excel != null) {
            db.updateDocumentFamilyExcelTemplate("demo_pack", excel.toAbsolutePath().toString());
        }
        db.insertImportedPageKind(
                "demo_pack",
                "demo_cover",
                "演示封面",
                "封面页",
                15,
                new PageRuleConfig(90, 85, " ", PairMode.HORIZONTAL, 70, 110),
                List.of(new SlotDefinition(
                        "part_name", "零部件名称", 1,
                        10, 20, 100, 40, "text", "")));
    }
}
