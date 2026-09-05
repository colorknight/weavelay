package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidOcr4jEngineTest {

    @Test
    void recognizesWithCharBoxesWhenModelsAndSampleExist() throws Exception {
        Path modelDir = PpOcrV5ModelPaths.defaultModelDir();
        Path sample = Paths.get("E:/WeaveLay2/docs/1.png");
        Assumptions.assumeTrue(PpOcrV5ModelPaths.isReady(modelDir), "ppocrv6 models missing");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "sample image missing");

        RapidOcrService service = new RapidOcrService();
        service.applyRuntime(new OcrRuntimeConfig(modelDir));
        List<OcrRow> rows = service.recognizeImage(sample, "smoke");
        assertFalse(rows.isEmpty());

        boolean anyTokens = false;
        for (OcrRow row : rows) {
            OcrTokenMeta meta = OcrTokenMeta.fromMetas(row.getMetas());
            assertNotNull(meta, "expected token metas on: " + row.getFeature());
            assertTrue(meta.getTokens().size() > 0);
            anyTokens = true;
            OcrTokenBox token = meta.getTokens().get(0);
            assertTrue(token.getEndX() >= token.getStartX());
            assertTrue(token.getEndY() >= token.getStartY());
        }
        assertTrue(anyTokens);
    }
}
