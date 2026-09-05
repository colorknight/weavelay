package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * RapidOCR4j 进程内 ONNX：PP-OCRv6 + 字/词级 bbox。
 * <p>单引擎：双引擎会再占一份 Det/Rec，内存带宽打满后单次推理反而更慢。
 */
public final class RapidOcrService implements OcrService {

    private final Object lock = new Object();
    private volatile RapidOcr4jEngine engine;
    private volatile Path engineModelDir;
    private volatile OcrParamSettings params = OcrParamSettings.defaults();
    private volatile OcrRuntimeConfig runtime = new OcrRuntimeConfig();

    public RapidOcrService() {
    }

    public RapidOcrService(OcrParamSettings params) {
        applyParams(params);
    }

    public static void prepareNativeRuntime() {
        NativeRuntimeBootstrap.prepareClassLoader();
    }

    public void applyParams(OcrParamSettings settings) {
        this.params = settings == null ? OcrParamSettings.defaults() : settings.copy();
    }

    public OcrParamSettings getParams() {
        return params.copy();
    }

    public void applyRuntime(OcrRuntimeConfig config) {
        OcrRuntimeConfig next = config == null ? new OcrRuntimeConfig() : config.copy();
        synchronized (lock) {
            if (!sameModelDir(next.resolveV6ModelDir(), runtime.resolveV6ModelDir())) {
                shutdownEngine();
            }
            runtime = next;
        }
    }

    @Override
    public void warmupInBackground(java.util.function.Consumer<String> status) {
        OcrRuntimeConfig current = runtime;
        OcrParamSettings paramSnapshot = params.copy();
        Path modelDir = current.resolveV6ModelDir();
        RapidOcr4jEngine host = engineHost(modelDir);
        if (host.isReady(modelDir)) {
            return;
        }
        host.scheduleWarmup(modelDir, paramSnapshot, status);
    }

    private void shutdownEngine() {
        RapidOcr4jEngine host = engine;
        engine = null;
        engineModelDir = null;
        if (host != null) {
            host.shutdown();
        }
    }

    public void validateRuntime(OcrRuntimeConfig config) throws java.io.IOException {
        if (config != null) {
            PpOcrV5ModelInstaller.prepareModelDir(config.resolveV6ModelDir());
        }
    }

    @Override
    public void cancelInFlightRecognition() {
        shutdownEngine();
    }

    @Override
    public List<OcrRow> recognizeImage(Path imagePath, String streamName) throws Exception {
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            return Collections.emptyList();
        }
        OcrParamSettings current = params;
        Path modelDir = runtime.resolveV6ModelDir();
        RapidOcr4jEngine host = engineHost(modelDir);
        if (!host.isReady(modelDir)) {
            host.warmup(modelDir, current, null);
        }
        List<OcrRow> rows = host.recognize(imagePath, current, streamName, current.getMinBoxScore());
        OcrCoordNormalizer.alignToSourceImage(rows, imagePath, current);
        return rows;
    }

    @Override
    public List<OcrRow> recognizeImageBytes(byte[] imageBytes, String streamName) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            return Collections.emptyList();
        }
        OcrParamSettings current = params;
        Path modelDir = runtime.resolveV6ModelDir();
        RapidOcr4jEngine host = engineHost(modelDir);
        if (!host.isReady(modelDir)) {
            host.warmup(modelDir, current, null);
        }
        java.awt.image.BufferedImage img;
        try {
            img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        } catch (Exception ex) {
            img = null;
        }
        List<OcrRow> rows;
        if (img != null) {
            rows = host.recognizeImage(img, current, streamName, current.getMinBoxScore());
            OcrCoordNormalizer.alignToSourceImage(
                    rows, img.getWidth(), img.getHeight(), alignParams(current, img.getWidth(), img.getHeight()));
        } else {
            rows = host.recognizeBytes(imageBytes, current, streamName, current.getMinBoxScore());
        }
        return rows;
    }

    /**
     * 已解码图像直传引擎，避免 PNG 编解码往返（识别参数与字节路径相同，不改质量）。
     */
    public List<OcrRow> recognizeBufferedImage(
            java.awt.image.BufferedImage image, String streamName) throws Exception {
        return recognizeBufferedImage(image, streamName, null);
    }

    public List<OcrRow> recognizeBufferedImage(
            java.awt.image.BufferedImage image, String streamName, OcrParamSettings override)
            throws Exception {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return Collections.emptyList();
        }
        OcrParamSettings current = override != null ? override : params;
        Path modelDir = runtime.resolveV6ModelDir();
        RapidOcr4jEngine host = engineHost(modelDir);
        if (!host.isReady(modelDir)) {
            host.warmup(modelDir, current, null);
        }
        List<OcrRow> rows = host.recognizeImage(image, current, streamName, current.getMinBoxScore());
        OcrCoordNormalizer.alignToSourceImage(
                rows, image.getWidth(), image.getHeight(),
                alignParams(current, image.getWidth(), image.getHeight()));
        return rows;
    }

    /** 大图档内部 Global.max=2000 时，给坐标回正用同一上限。 */
    private static OcrParamSettings alignParams(OcrParamSettings current, int w, int h) {
        if (current == null) {
            return OcrParamSettings.defaults();
        }
        if (current.getMaxSideLen() > 0) {
            return current;
        }
        if (Math.max(w, h) >= 1800) {
            OcrParamSettings copy = current.copy();
            copy.setMaxSideLen(2000);
            return copy;
        }
        return current;
    }

    private RapidOcr4jEngine engineHost(Path modelDir) {
        synchronized (lock) {
            if (engine == null || !sameModelDir(engineModelDir, modelDir)) {
                if (engine != null) {
                    engine.shutdown();
                }
                engine = new RapidOcr4jEngine();
                engineModelDir = modelDir;
            }
            return engine;
        }
    }

    private static boolean sameModelDir(Path a, Path b) {
        if (a == null || b == null) {
            return a == b;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Exception ex) {
            return a.equals(b);
        }
    }
}
