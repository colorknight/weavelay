package com.weavelay.ocr.formula;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内 PP-FormulaNet ONNX 冒烟。
 */
class PpFormulaNetOnnxTest {

    private static final PpFormulaNetPaths.ModelFiles MODELS = PpFormulaNetPaths.resolve();

    @Test
    void decodeKnownTokenIds() throws Exception {
        Assumptions.assumeTrue(MODELS != null, "PP-FormulaNet models missing");
        long[] ids = {
                0, 47, 243, 39, 243, 42, 243, 46, 243, 85, 243, 113, 243, 38, 243, 115,
                243, 84, 243, 113, 243, 33, 243, 38, 243, 36, 243, 41, 243, 115, 2
        };
        String latex = PpFormulaNetOnnxEngine.postProcess(
                PpFormulaNetOnnxEngine.decodeOnly(MODELS.tokenizer(), ids));
        System.out.println("[PpFormulaNet] decode-only => " + latex);
        assertTrue(latex.contains("9148") || latex.contains("148"));
        assertTrue(latex.contains("_{0}") || latex.contains("_0"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void recognizeDebugFormulaImages() throws Exception {
        Assumptions.assumeTrue(MODELS != null, "PP-FormulaNet models missing");
        Path[] images = {
                Paths.get("E:/WeaveLay2/formula-debug/formula_1784016903132_1.png"),
                Paths.get("E:/WeaveLay2/formula-debug/formula_1784019601166_1.png"),
                MODELS.backbone().getParent().getParent().getParent().resolve("3.png")
        };
        try (PpFormulaNetOnnxEngine eng = new PpFormulaNetOnnxEngine(
                MODELS.backbone(), MODELS.head(), MODELS.tokenizer())) {
            int ok = 0;
            for (Path img : images) {
                if (!Files.isRegularFile(img)) {
                    continue;
                }
                long t0 = System.currentTimeMillis();
                String latex = eng.recognize(img, 96);
                long dt = System.currentTimeMillis() - t0;
                System.out.println("[PpFormulaNet] " + img.getFileName() + " => " + latex + " (" + dt + "ms)");
                if (latex != null && !latex.isBlank()) {
                    ok++;
                }
            }
            assertTrue(ok > 0);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void bootstrapAttachesLocalRecognizer() {
        Assumptions.assumeTrue(MODELS != null, "PP-FormulaNet models missing");
        var svc = new com.weavelay.core.formula.FormulaRecognitionService();
        assertTrue(FormulaOnnxBootstrap.attach(svc));
        assertTrue(svc.hasLocalRecognizer());
        Path img = Paths.get("E:/WeaveLay2/formula-debug/formula_1784019601166_1.png");
        Assumptions.assumeTrue(Files.isRegularFile(img));
        try {
            byte[] png = Files.readAllBytes(img);
            String latex = svc.recognizeSingle(png);
            System.out.println("[PpFormulaNet] via FormulaRecognitionService => " + latex);
            assertFalse(latex == null || latex.isBlank());
            assertTrue(latex.contains("varnothing") || latex.contains("18"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
