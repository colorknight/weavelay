package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

class CurrentEngineTableProbeTest {
    @Test
    void recognizeTableCropWithServiceDefaults() throws Exception {
        Path sample = Paths.get("E:/tmp/weavelay/ocr-pre/174215_apply_03_pad50.png");
        Path modelDir = PpOcrV5ModelPaths.defaultModelDir();
        Assumptions.assumeTrue(PpOcrV5ModelPaths.isReady(modelDir));
        Assumptions.assumeTrue(Files.isRegularFile(sample));

        RapidOcrService.prepareNativeRuntime();
        RapidOcrService svc = new RapidOcrService();
        svc.applyRuntime(new OcrRuntimeConfig(modelDir));
        OcrParamSettings p = OcrParamSettings.defaults();
        p.setPadding(0);
        p.setBoxScoreThresh(0.2f);
        p.setBoxThresh(0.35f);
        p.setUnClipRatio(1.8f);
        p.setMaxSideLen(0);
        p.setReturnWordBox(false);
        svc.applyParams(p);

        byte[] bytes = Files.readAllBytes(sample);
        // 第一次含大图档懒加载
        svc.recognizeImageBytes(bytes, "apply");
        long t0 = System.nanoTime();
        List<OcrRow> rows = svc.recognizeImageBytes(bytes, "apply");
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("service apply (warm) %.2fs rows=%d%n", sec, rows.size());
        boolean left1 = false;
        boolean right4 = false;
        for (OcrRow r : rows) {
            String t = r.getFeature() == null ? "" : r.getFeature().trim();
            if (t.equals("1")) {
                left1 = true;
            }
            if (t.equals("4")) {
                right4 = true;
            }
            if (t.matches("\\d{1,3}")) {
                System.out.printf(
                        "  [%s] conf=%.3f x=%.0f%n",
                        t, r.getProb(), r.getStartX());
            }
        }
        System.out.printf("left1=%s right4=%s%n", left1, right4);
        org.junit.jupiter.api.Assertions.assertTrue(left1, "left 1");
        org.junit.jupiter.api.Assertions.assertTrue(right4, "right 4");
        org.junit.jupiter.api.Assertions.assertTrue(sec < 6.5, "warm OCR should be <6.5s, was " + sec);
    }
}
