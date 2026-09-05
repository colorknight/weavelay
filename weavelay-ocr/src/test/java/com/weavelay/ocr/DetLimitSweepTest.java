package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;
import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.ParamConfig;
import io.github.hzkitty.entity.RecResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线扫参：大表裁剪上左「1」/右「4」与 Det.limitSideLen、Global.maxSideLen 的关系。
 * 仅本地有样图时跑：{@code mvn -pl weavelay-ocr -Dtest=DetLimitSweepTest test}
 */
class DetLimitSweepTest {

    private static final Path SAMPLE = Paths.get("E:/tmp/weavelay/ocr-pre/174215_apply_03_pad50.png");

    @Test
    void sweepLimitsOnTableCrop() throws Exception {
        Path modelDir = PpOcrV5ModelPaths.defaultModelDir();
        Assumptions.assumeTrue(PpOcrV5ModelPaths.isReady(modelDir), "models missing");
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "sample missing: " + SAMPLE);

        RapidOcrService.prepareNativeRuntime();
        BufferedImage img = ImageIO.read(SAMPLE.toFile());
        System.out.printf("sample %dx%d%n", img.getWidth(), img.getHeight());

        // 用户 prefs 近似
        float boxScore = 0.2f;
        float boxThresh = 0.35f;
        float unclip = 1.8f;

        int[] globalMax = {100_000, 2000, 3200};
        int[] detLimits = {736, 960, 1100, 1200, 1280};

        for (int gMax : globalMax) {
            for (int lim : detLimits) {
                long t0 = System.nanoTime();
                List<String> hits = runOnce(modelDir, SAMPLE, gMax, lim, boxScore, boxThresh, unclip);
                double sec = (System.nanoTime() - t0) / 1e9;
                boolean left1 = hits.stream().anyMatch(s -> isLoneDigit(s, "1"));
                boolean right4 = hits.stream().anyMatch(s -> isLoneDigit(s, "4"));
                System.out.printf(
                        "gMax=%5d lim=%4d %.2fs left1=%s right4=%s digits=%s%n",
                        gMax, lim, sec, left1, right4, summarizeDigits(hits));
            }
        }
    }

    private static List<String> runOnce(
            Path modelDir,
            Path image,
            int globalMaxSide,
            int detLimit,
            float boxScore,
            float boxThresh,
            float unclip) throws Exception {
        OcrConfig config = new OcrConfig();
        config.Det.modelPath = modelDir.resolve(PpOcrV5ModelInstaller.DET_FILE).toString();
        config.Rec.modelPath = modelDir.resolve(PpOcrV5ModelInstaller.REC_FILE).toString();
        config.Rec.recKeysPath = modelDir.resolve(PpOcrV5ModelInstaller.KEYS_FILE).toString();
        config.Cls.modelPath = modelDir.resolve(PpOcrV5ModelInstaller.CLS_FILE).toString();
        config.Global.useCls = false;
        config.Global.maxSideLen = globalMaxSide;
        config.Global.textScore = boxScore;
        config.Det.boxThresh = boxScore;
        config.Det.thresh = boxThresh;
        config.Det.unclipRatio = unclip;
        config.Det.limitType = "min";
        config.Det.limitSideLen = detLimit;

        RapidOCR ocr = RapidOCR.create(config);
        ParamConfig param = new ParamConfig();
        param.setBoxThresh(boxScore);
        param.setUnclipRatio(unclip);
        param.setReturnWordBox(false);
        param.setUseCls(false);

        OcrResult result = ocr.run(image, param);
        List<String> texts = new ArrayList<>();
        if (result == null || result.getRecRes() == null) {
            return texts;
        }
        for (RecResult rec : result.getRecRes()) {
            if (rec == null || rec.getText() == null) {
                continue;
            }
            String t = rec.getText().trim();
            if (!t.isEmpty()) {
                texts.add(t);
            }
        }
        return texts;
    }

    private static boolean isLoneDigit(String text, String digit) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.equals(digit);
    }

    private static String summarizeDigits(List<String> hits) {
        StringBuilder sb = new StringBuilder();
        for (String t : hits) {
            if (t.matches("\\d{1,3}")) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
