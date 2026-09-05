package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;
import io.github.hzkitty.RapidOCR;
import io.github.hzkitty.entity.OcrConfig;
import io.github.hzkitty.entity.OcrResult;
import io.github.hzkitty.entity.ParamConfig;
import io.github.hzkitty.entity.RecResult;
import io.github.hzkitty.entity.WordBoxResult;
import org.opencv.core.Point;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 进程内 RapidOCR4j（PP-OCRv6 ONNX + 字/词框）。
 *
 * <p>按图幅分两档引擎（常驻，避免来回重建）：
 * <ul>
 *   <li>小图（长边 &lt; 1800）：Det.limit=736，不缩边 — 首页字段不被拉爆</li>
 *   <li>大图（表裁剪等）：Global.max=2000 + Det.limit=1200 — 扫参比全分辨率更快且保住细「1」「4」</li>
 * </ul>
 */
final class RapidOcr4jEngine {

    /** 用户 maxSideLen=0 时：小图档关闭全局缩边（盖住库默认 2000）。 */
    private static final int GLOBAL_MAX_SIDE_OFF = 100_000;

    /** 大图表裁剪：先压到此长边再靠 Det 1200 回一点分辨率。 */
    private static final int LARGE_GLOBAL_MAX_SIDE = 2000;

    private static final int DET_LIMIT_SMALL = 736;
    private static final int DET_LIMIT_LARGE = 1200;

    /** 长边达到此值走大图档。 */
    private static final int LARGE_MAX_SIDE_PX = 1800;

    private final Object lock = new Object();
    private volatile RapidOCR ocrSmall;
    private volatile RapidOCR ocrLarge;
    private volatile Path modelDir;
    private volatile String paramsFingerprint;
    private volatile boolean ready;
    private final AtomicReference<CompletableFuture<Void>> warmupFuture =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    boolean isReady(Path expectedModelDir) {
        Path normalized = normalize(expectedModelDir);
        synchronized (lock) {
            return ready && ocrSmall != null && modelDir != null && modelDir.equals(normalized);
        }
    }

    void scheduleWarmup(Path modelDir, OcrParamSettings params, Consumer<String> status) {
        Path normalized = normalize(modelDir);
        synchronized (lock) {
            if (isReady(normalized)) {
                return;
            }
            CompletableFuture<Void> inFlight = warmupFuture.get();
            if (inFlight != null && !inFlight.isDone()) {
                return;
            }
        }
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
            try {
                warmup(normalized, params, status);
            } catch (Exception ex) {
                if (status != null) {
                    status.accept("PP-OCRv6 加载失败: " + ex.getMessage());
                }
                shutdown();
                throw new IllegalStateException("PP-OCRv6 预热失败", ex);
            }
        });
        warmupFuture.set(task);
    }

    void warmup(Path modelDir, OcrParamSettings params, Consumer<String> status) throws Exception {
        Path normalized = normalize(modelDir);
        synchronized (lock) {
            if (isReady(normalized)) {
                return;
            }
            if (status != null) {
                status.accept("正在加载 PP-OCRv6 引擎…");
            }
            OcrParamSettings settings = params == null ? OcrParamSettings.defaults() : params;
            ensureEngines(normalized, settings);
            Path warmup = PpOcrV5WarmupImage.ensure();
            recognizeLocked(warmup, settings, "warmup", 0f);
            // 预加载大图档：同参数识别，只把首次大表裁剪的模型加载挪到启动期
            warmLargeEngine(settings);
            ready = true;
            if (status != null) {
                status.accept("PP-OCRv6 已就绪");
            }
        }
    }

    /** 用 ≥1800 白图走一遍大档路径，触发 ocrLarge 创建（不改变后续真实图识别结果）。 */
    private void warmLargeEngine(OcrParamSettings settings) {
        try {
            BufferedImage blank = new BufferedImage(1800, 1800, BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g = blank.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, 1800, 1800);
            } finally {
                g.dispose();
            }
            recognizeLockedImage(blank, settings, "warmup", 0f);
        } catch (Exception ignored) {
            // 预热失败不影响主路径；首次大裁剪仍会懒加载
        }
    }

    List<OcrRow> recognize(Path imagePath, OcrParamSettings params, String streamName, float minBoxScore)
            throws Exception {
        awaitWarmup();
        synchronized (lock) {
            Path dir = modelDir;
            if (dir == null) {
                throw new IllegalStateException("PP-OCRv6 引擎未初始化");
            }
            OcrParamSettings settings = params == null ? OcrParamSettings.defaults() : params;
            ensureEngines(dir, settings);
            return recognizeLocked(imagePath, settings, streamName, minBoxScore);
        }
    }

    List<OcrRow> recognizeBytes(
            byte[] imageBytes, OcrParamSettings params, String streamName, float minBoxScore)
            throws Exception {
        awaitWarmup();
        synchronized (lock) {
            Path dir = modelDir;
            if (dir == null) {
                throw new IllegalStateException("PP-OCRv6 引擎未初始化");
            }
            OcrParamSettings settings = params == null ? OcrParamSettings.defaults() : params;
            ensureEngines(dir, settings);
            return recognizeLockedBytes(imageBytes, settings, streamName, minBoxScore);
        }
    }

    List<OcrRow> recognizeImage(
            BufferedImage image, OcrParamSettings params, String streamName, float minBoxScore)
            throws Exception {
        awaitWarmup();
        synchronized (lock) {
            Path dir = modelDir;
            if (dir == null) {
                throw new IllegalStateException("PP-OCRv6 引擎未初始化");
            }
            OcrParamSettings settings = params == null ? OcrParamSettings.defaults() : params;
            ensureEngines(dir, settings);
            if (image == null) {
                return Collections.emptyList();
            }
            return recognizeLockedImage(image, settings, streamName, minBoxScore);
        }
    }

    void shutdown() {
        synchronized (lock) {
            ocrSmall = null;
            ocrLarge = null;
            modelDir = null;
            paramsFingerprint = null;
            ready = false;
            warmupFuture.set(CompletableFuture.completedFuture(null));
        }
    }

    private void awaitWarmup() throws Exception {
        CompletableFuture<Void> future = warmupFuture.get();
        if (future != null) {
            future.get();
        }
    }

    private void ensureEngines(Path modelDir, OcrParamSettings params) throws Exception {
        Path normalized = normalize(modelDir);
        String fingerprint = paramsFingerprint(normalized, params);
        if (ocrSmall != null
                && this.modelDir != null
                && this.modelDir.equals(normalized)
                && fingerprint.equals(paramsFingerprint)) {
            return;
        }
        PpOcrV5ModelInstaller.prepareModelDir(normalized);
        // 小图档常驻；大图档首次大裁剪时再加载，避免首页白付双倍模型内存/时间
        ocrSmall = createOcr(normalized, params, DET_LIMIT_SMALL, globalMaxForSmall(params));
        ocrLarge = null;
        this.modelDir = normalized;
        this.paramsFingerprint = fingerprint;
        ready = false;
    }

    private RapidOCR pickEngine(int width, int height, OcrParamSettings params) {
        if (!isLargeCrop(width, height)) {
            return ocrSmall;
        }
        if (ocrLarge == null) {
            ocrLarge = createOcr(modelDir, params, DET_LIMIT_LARGE, globalMaxForLarge(params));
        }
        return ocrLarge;
    }

    private static RapidOCR createOcr(
            Path modelDir, OcrParamSettings params, int detLimit, int globalMaxSide) {
        OcrConfig config = new OcrConfig();
        config.Det.modelPath = modelDir.resolve(PpOcrV5ModelInstaller.DET_FILE).toString();
        config.Rec.modelPath = modelDir.resolve(PpOcrV5ModelInstaller.REC_FILE).toString();
        config.Rec.recKeysPath = modelDir.resolve(PpOcrV5ModelInstaller.KEYS_FILE).toString();
        config.Global.useCls = false;
        config.Cls.modelPath = modelDir.resolve(PpOcrV5ModelInstaller.CLS_FILE).toString();
        if (params != null) {
            config.Global.textScore = params.getBoxScoreThresh();
            config.Det.boxThresh = params.getBoxScoreThresh();
            config.Det.thresh = params.getBoxThresh();
            config.Det.unclipRatio = saneUnclip(params.getUnClipRatio());
        }
        config.Global.maxSideLen = globalMaxSide;
        config.Det.limitType = "min";
        config.Det.limitSideLen = detLimit;
        return RapidOCR.create(config);
    }

    private static boolean isLargeCrop(int width, int height) {
        return Math.max(width, height) >= LARGE_MAX_SIDE_PX;
    }

    private List<OcrRow> recognizeLocked(
            Path imagePath,
            OcrParamSettings params,
            String streamName,
            float minBoxScore) throws Exception {
        BufferedImage src = ImageIO.read(imagePath.toFile());
        if (src == null) {
            return Collections.emptyList();
        }
        return recognizeLockedImage(src, params, streamName, minBoxScore);
    }

    private List<OcrRow> recognizeLockedBytes(
            byte[] imageBytes,
            OcrParamSettings params,
            String streamName,
            float minBoxScore) throws Exception {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        if (src == null) {
            OcrParamSettings settings = params == null ? OcrParamSettings.defaults() : params;
            ParamConfig param = buildParamConfig(settings, streamName);
            OcrResult result = ocrSmall.run(imageBytes, param);
            return toRows(result, settings, streamName, minBoxScore, 0);
        }
        return recognizeLockedImage(src, params, streamName, minBoxScore);
    }

    private List<OcrRow> recognizeLockedImage(
            BufferedImage src,
            OcrParamSettings params,
            String streamName,
            float minBoxScore) throws Exception {
        OcrParamSettings settings = params == null ? OcrParamSettings.defaults() : params;
        // 大表左/右列贴边短数字（「1」「4」）依赖白边；此前为加速对大图跳过 pad，会丢左侧「1」
        int pad = Math.max(0, settings.getPadding());
        BufferedImage runImg = src;
        if (pad > 0) {
            BufferedImage padded = padInMemory(src, pad);
            if (padded != null) {
                runImg = padded;
            } else {
                pad = 0;
            }
        }
        RapidOCR engine = pickEngine(runImg.getWidth(), runImg.getHeight(), settings);
        ParamConfig param = buildParamConfig(settings, streamName);
        OcrResult result = engine.run(runImg, param);
        return toRows(result, settings, streamName, minBoxScore, pad);
    }

    private static ParamConfig buildParamConfig(OcrParamSettings settings, String streamName) {
        ParamConfig param = new ParamConfig();
        param.setBoxThresh(settings.getBoxScoreThresh());
        param.setUnclipRatio(saneUnclip(settings.getUnClipRatio()));
        param.setReturnWordBox(wantWordBox(settings, streamName));
        param.setReturnWordLevel(settings.isReturnWordLevel());
        param.setUseCls(false);
        return param;
    }

    /**
     * apply/crop/export 只要行框；字框 Rec 更慢。公式混合路径仍要字框。
     */
    private static boolean wantWordBox(OcrParamSettings settings, String streamName) {
        if ("formula-mixed".equals(streamName)) {
            return true;
        }
        if (streamName != null && (
                streamName.equals("apply")
                        || streamName.equals("crop")
                        || streamName.equals("warmup")
                        || streamName.equals("export-tbl")
                        || streamName.equals("formula-digit")
                        || streamName.startsWith("export"))) {
            return false;
        }
        return settings.isReturnWordBox();
    }

    private List<OcrRow> toRows(
            OcrResult result,
            OcrParamSettings settings,
            String streamName,
            float minBoxScore,
            int pad) {
        if (result == null || result.getRecRes() == null || result.getRecRes().isEmpty()) {
            return Collections.emptyList();
        }

        List<OcrRow> rows = new ArrayList<>(result.getRecRes().size());
        OcrTokenMeta.Level level = settings.isReturnWordLevel()
                ? OcrTokenMeta.Level.WORD
                : OcrTokenMeta.Level.CHAR;
        boolean withWordBox = wantWordBox(settings, streamName);
        for (RecResult rec : result.getRecRes()) {
            if (rec == null) {
                continue;
            }
            float conf = rec.getConfidence();
            if (conf < minBoxScore) {
                continue;
            }
            String text = rec.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            double[] box = bounds(rec.getDtBoxes());
            OcrTokenMeta meta = null;
            if (withWordBox) {
                meta = toTokenMeta(rec.getWordBoxResult(), level);
            }
            if (pad > 0) {
                shift(box, -pad);
                if (meta != null) {
                    for (OcrTokenBox tok : meta.getTokens()) {
                        tok.shift(-pad, -pad);
                    }
                }
            }
            rows.add(new OcrRow(streamName, text, conf, box[0], box[1], box[2], box[3], meta));
        }
        return rows;
    }

    private static float saneUnclip(float unclip) {
        return unclip < 1.2f ? 1.5f : unclip;
    }

    private static int globalMaxForSmall(OcrParamSettings params) {
        if (params != null && params.getMaxSideLen() > 0) {
            return params.getMaxSideLen();
        }
        return GLOBAL_MAX_SIDE_OFF;
    }

    private static int globalMaxForLarge(OcrParamSettings params) {
        if (params != null && params.getMaxSideLen() > 0) {
            return params.getMaxSideLen();
        }
        // 扫参：全分辨率(~8s) 与 2000+1200(~4.8s) 都能检出 1/4；大图档取后者。
        return LARGE_GLOBAL_MAX_SIDE;
    }

    private static BufferedImage padInMemory(BufferedImage src, int pad) {
        if (src == null) {
            return null;
        }
        int safePad = Math.max(0, pad);
        if (safePad <= 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w + safePad * 2, h + safePad * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, safePad, safePad, null);
        g.dispose();
        return out;
    }

    private static void shift(double[] box, double delta) {
        box[0] += delta;
        box[1] += delta;
        box[2] += delta;
        box[3] += delta;
    }

    private static String paramsFingerprint(Path modelDir, OcrParamSettings params) {
        OcrParamSettings p = params == null ? OcrParamSettings.defaults() : params;
        return modelDir
                + "|" + p.getMaxSideLen()
                + "|" + p.getBoxScoreThresh()
                + "|" + p.getBoxThresh()
                + "|" + p.getUnClipRatio()
                + "|tier=small/" + DET_LIMIT_SMALL + "+large/" + DET_LIMIT_LARGE
                + "@" + LARGE_GLOBAL_MAX_SIDE;
    }

    private static OcrTokenMeta toTokenMeta(WordBoxResult wordBox, OcrTokenMeta.Level level) {
        if (wordBox == null
                || wordBox.getWordBoxContentList() == null
                || wordBox.getSortedWordBoxList() == null) {
            return null;
        }
        List<String> texts = wordBox.getWordBoxContentList();
        List<Point[]> boxes = wordBox.getSortedWordBoxList();
        List<Float> confs = wordBox.getConfList();
        int n = Math.min(texts.size(), boxes.size());
        if (n == 0) {
            return null;
        }
        List<OcrTokenBox> tokens = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double[] b = bounds(boxes.get(i));
            double conf = confs != null && i < confs.size() && confs.get(i) != null
                    ? confs.get(i)
                    : 0.0;
            tokens.add(new OcrTokenBox(texts.get(i), conf, b[0], b[1], b[2], b[3]));
        }
        return new OcrTokenMeta(level, tokens);
    }

    private static double[] bounds(Point[] pts) {
        if (pts == null || pts.length == 0) {
            return new double[]{0, 0, 0, 0};
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point p : pts) {
            if (p == null) {
                continue;
            }
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        if (!Double.isFinite(minX)) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private static Path normalize(Path modelDir) {
        if (modelDir == null) {
            return null;
        }
        return modelDir.toAbsolutePath().normalize();
    }
}
