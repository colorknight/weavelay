package com.weavelay.ocr.layout;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.*;

import static org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_core.copyMakeBorder;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * PP-DocLayoutV3 ONNX 版面检测服务.
 *
 * <p>模型: /model/layout/PP-DocLayoutV3_ir8.onnx (DETR 架构)
 * <br>预处理: Resize 800×800, RGB, /255, 无 ImageNet 归一化
 * <br>输入: im_shape[1,2], image[1,3,800,800], scale_factor[1,2]
 * <br>输出: N×7 [label,score,xmin,ymin,xmax,ymax,read_order]
 *
 * <p>公式类标签: display_formula, inline_formula
 */
public final class PpDocLayoutOnnxService implements AutoCloseable {

    private static final int INPUT_SIZE = 800;
    private static final String DEFAULT_MODEL = "/model/layout/PP-DocLayoutV3_ir8.onnx";
    private static final String DEFAULT_CONFIG = "/model/layout/config.json";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final List<String> labelNames;
    private final float defaultScoreThreshold;

    /** 使用 classpath 默认路径加载. */
    public PpDocLayoutOnnxService() throws IOException, OrtException {
        this(DEFAULT_MODEL, DEFAULT_CONFIG);
    }

    public PpDocLayoutOnnxService(String modelClasspath, String configClasspath)
            throws IOException, OrtException {
        Objects.requireNonNull(modelClasspath, "modelClasspath");
        Objects.requireNonNull(configClasspath, "configClasspath");
        this.env = com.weavelay.ocr.OrtCpuSessions.environment();
        byte[] modelBytes = readClasspathBytes(modelClasspath);
        OrtSession.SessionOptions opts = com.weavelay.ocr.OrtCpuSessions.newSessionOptions();
        this.session = env.createSession(modelBytes, opts);
        this.labelNames = loadLabelList(configClasspath);
        this.defaultScoreThreshold = loadDrawThreshold(configClasspath);
    }

    public float getDefaultScoreThreshold() { return defaultScoreThreshold; }

    /**
     * 对 PNG 字节做版式检测.
     */
    public PpDocLayoutPageResult detect(byte[] pngBytes) throws IOException, OrtException {
        return detect(pngBytes, defaultScoreThreshold, false);
    }

    /**
     * 对 PNG 字节做版式检测.
     *
     * @param pngBytes      PNG 图像字节
     * @param scoreThreshold 置信度阈值 (默认 0.5)
     * @param applyEnhance   是否应用图像增强 (去噪+锐化+二值化)
     */
    public PpDocLayoutPageResult detect(byte[] pngBytes, float scoreThreshold, boolean applyEnhance)
            throws IOException, OrtException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) {
            throw new IOException("无法解析 PNG 图像");
        }
        return detectImage(img, scoreThreshold, applyEnhance);
    }

    /** 对 BufferedImage 做版式检测. */
    public PpDocLayoutPageResult detectImage(BufferedImage image) throws OrtException {
        return detectImage(image, defaultScoreThreshold, false);
    }

    public PpDocLayoutPageResult detectImage(BufferedImage image, float scoreThreshold, boolean applyEnhance)
            throws OrtException {
        Objects.requireNonNull(image, "image");
        BufferedImage rgb = ensureRgb(image);
        int origW = rgb.getWidth();
        int origH = rgb.getHeight();

        float[] chw;
        float scale;
        try (Mat src = bufferedImageRgbToBgrMat(rgb);
             Mat preprocessed = preprocessForModel(src, applyEnhance)) {
            chw = imageToChw01(preprocessed);
        }

        scale = INPUT_SIZE / (float) Math.max(origW, origH);

        try (OnnxTensor imShape =
                     OnnxTensor.createTensor(env, floatBuffer2(new float[]{INPUT_SIZE, INPUT_SIZE}), new long[]{1, 2});
             OnnxTensor imageTensor =
                     OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
             OnnxTensor scaleTensor =
                     OnnxTensor.createTensor(env, floatBuffer2(new float[]{scale, scale}), new long[]{1, 2})) {

            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("im_shape", imShape);
            feeds.put("image", imageTensor);
            feeds.put("scale_factor", scaleTensor);

            try (OrtSession.Result result = session.run(feeds)) {
                float[][] matrix = readFirstOutputMatrix(result);
                List<PpDocLayoutBox> boxes = decodeBoxes(matrix, scoreThreshold);
                return new PpDocLayoutPageResult(origW, origH, boxes);
            }
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    // ── 内部工具方法 ──

    private static byte[] readClasspathBytes(String path) throws IOException {
        InputStream in = PpDocLayoutOnnxService.class.getResourceAsStream(path);
        if (in == null) {
            throw new IOException("classpath 资源不存在: " + path);
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private static List<String> loadLabelList(String configClasspath) throws IOException {
        InputStream in = PpDocLayoutOnnxService.class.getResourceAsStream(configClasspath);
        if (in == null) throw new IOException("classpath 资源不存在: " + configClasspath);
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            JsonNode list = root.get("label_list");
            if (list == null || !list.isArray())
                throw new IOException("config 缺少 label_list: " + configClasspath);
            List<String> out = new ArrayList<>();
            for (JsonNode n : list) out.add(n.asText());
            return Collections.unmodifiableList(out);
        } finally {
            in.close();
        }
    }

    private static float loadDrawThreshold(String configClasspath) throws IOException {
        InputStream in = PpDocLayoutOnnxService.class.getResourceAsStream(configClasspath);
        if (in == null) return 0.5f;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            JsonNode t = root.get("draw_threshold");
            return (t != null && t.isNumber()) ? (float) t.asDouble() : 0.5f;
        } finally {
            in.close();
        }
    }

    private List<PpDocLayoutBox> decodeBoxes(float[][] matrix, float scoreThreshold) {
        if (matrix == null || matrix.length == 0) return Collections.emptyList();
        List<PpDocLayoutBox> raw = new ArrayList<>();
        for (float[] row : matrix) {
            if (row == null || row.length < 7) continue;
            float score = row[1];
            if (score < scoreThreshold || Float.isNaN(score)) continue;
            int li = Math.round(row[0]);
            String name = li >= 0 && li < labelNames.size() ? labelNames.get(li) : ("class_" + li);
            raw.add(new PpDocLayoutBox(li, name, score, row[2], row[3], row[4], row[5], row[6]));
        }
        raw.sort(Comparator.comparingDouble(PpDocLayoutBox::getReadOrder));
        return raw;
    }

    private static float[][] readFirstOutputMatrix(OrtSession.Result result) throws OrtException {
        OnnxValue v = result.get("fetch_name_0").orElse(null);
        if (v == null && result.size() > 0) v = result.get(0);
        if (v == null) throw new OrtException("无版式检测输出张量");
        if (!(v instanceof OnnxTensor)) throw new OrtException("首个输出不是张量");
        OnnxTensor t = (OnnxTensor) v;
        Object val = t.getValue();
        if (val instanceof float[][]) return (float[][]) val;
        throw new OrtException("不支持的输出类型: " + (val == null ? "null" : val.getClass()));
    }

    // ── 图像预处理 ──

    private static BufferedImage ensureRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try { g.drawImage(src, 0, 0, null); } finally { g.dispose(); }
        return rgb;
    }

    /**
     * NCHW, RGB, 值域 [0,1].
     */
    private static float[] imageToChw01(Mat bgr) {
        int w = bgr.cols(), h = bgr.rows();
        float[] chw = new float[3 * w * h];
        int stride = w * h;
        UByteIndexer idx = bgr.createIndexer();
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int b = idx.get(y, x, 0), g = idx.get(y, x, 1), r = idx.get(y, x, 2);
                    int i = y * w + x;
                    chw[i] = r / 255.0f;
                    chw[stride + i] = g / 255.0f;
                    chw[2 * stride + i] = b / 255.0f;
                }
            }
        } finally {
            idx.release();
        }
        return chw;
    }

    private static Mat bufferedImageRgbToBgrMat(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        Mat out = new Mat(h, w, CV_8UC3);
        UByteIndexer idx = out.createIndexer();
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    idx.put(y, x, 0, rgb & 0xff);
                    idx.put(y, x, 1, (rgb >> 8) & 0xff);
                    idx.put(y, x, 2, (rgb >> 16) & 0xff);
                }
            }
        } finally {
            idx.release();
        }
        return out;
    }

    private static Mat preprocessForModel(Mat srcBgr, boolean applyEnhance) {
        int w = srcBgr.cols(), h = srcBgr.rows();
        float scale = INPUT_SIZE / (float) Math.max(w, h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        Mat resized = new Mat();
        resize(srcBgr, resized, new Size(newW, newH), 0.0, 0.0, INTER_LINEAR);

        // Letterbox: 长边缩到 800, 短边黑边补齐
        int padRight = INPUT_SIZE - newW;
        int padBottom = INPUT_SIZE - newH;
        Mat out = new Mat();
        copyMakeBorder(resized, out, 0, padBottom, 0, padRight, BORDER_CONSTANT,
                new Scalar(0.0, 0.0, 0.0, 255.0));
        resized.close();
        return out;
    }

    private static FloatBuffer floatBuffer2(float[] two) {
        FloatBuffer fb = FloatBuffer.allocate(2);
        fb.put(two[0]);
        fb.put(two[1]);
        fb.rewind();
        return fb;
    }
}
