package com.weavelay.ocr.formula;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PP-FormulaNet_plus-M 进程内 ONNX 识别（backbone + head_fixed），不依赖 Python。
 */
public final class PpFormulaNetOnnxEngine implements AutoCloseable {

    static final int INPUT_SIZE = 384;
    static final int BOS = 0;
    static final int EOS = 2;
    static final int PAD = 1;
    static final int UNK = 3;

    private static final float NORM_MEAN = 0.7931f;
    private static final float NORM_STD = 0.1738f;
    /** 工业公差图一般很短；过大浪费自回归步数。 */
    private static final int DEFAULT_MAX_NEW_TOKENS = 96;

    private final OrtEnvironment env;
    private final byte[] headModelBytes;
    private OrtSession backbone;
    private final OrtSession[] headSlots = new OrtSession[2];
    private int headCursor;
    private final Object headLock = new Object();
    private final String[] id2token;
    private final Map<Character, Integer> unicodeToByte;
    private final OrtSession.SessionOptions sessionOpts;

    public PpFormulaNetOnnxEngine(Path backboneOnnx, Path headOnnx, Path tokenizerJson) throws OrtException, IOException {
        this.env = com.weavelay.ocr.OrtCpuSessions.environment();
        this.headModelBytes = Files.readAllBytes(headOnnx);
        this.sessionOpts = com.weavelay.ocr.OrtCpuSessions.newSessionOptions();
        this.backbone = env.createSession(backboneOnnx.toString(), sessionOpts);
        this.headSlots[0] = env.createSession(headModelBytes, sessionOpts);
        this.headSlots[1] = env.createSession(headModelBytes, sessionOpts);
        this.headCursor = 0;
        this.id2token = loadVocab(tokenizerJson);
        this.unicodeToByte = buildUnicodeToByte();
    }

    /** 模型齐全则打开，否则返回 null。 */
    public static PpFormulaNetOnnxEngine tryOpen() {
        PpFormulaNetPaths.ModelFiles files = PpFormulaNetPaths.resolve();
        if (files == null) {
            return null;
        }
        try {
            return new PpFormulaNetOnnxEngine(files.backbone(), files.head(), files.tokenizer());
        } catch (Exception e) {
            System.err.println("[PpFormulaNet] load failed: " + e.getMessage());
            return null;
        }
    }

    /** 识别 PNG 字节；线程安全。 */
    public synchronized String recognizePng(byte[] pngBytes) throws Exception {
        if (pngBytes == null || pngBytes.length == 0) {
            return "";
        }
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
        if (img == null) {
            return "";
        }
        return recognizeTensor(preprocess(img), DEFAULT_MAX_NEW_TOKENS);
    }

    public synchronized String recognize(Path imagePath, int maxNewTokens) throws Exception {
        float[][][][] nchw = preprocess(ImageIO.read(imagePath.toFile()));
        return recognizeTensor(nchw, maxNewTokens);
    }

    /**
     * head_fixed 用过就脏；双槽轮换。预热放在解码之后，避免和推理抢 OpenMP。
     */
    private OrtSession borrowHead() throws OrtException {
        synchronized (headLock) {
            int i = headCursor;
            headCursor = 1 - headCursor;
            if (headSlots[i] == null) {
                headSlots[i] = env.createSession(headModelBytes, sessionOpts);
            }
            OrtSession h = headSlots[i];
            headSlots[i] = null;
            return h;
        }
    }

    private void refillEmptyHeadSlots() {
        synchronized (headLock) {
            for (int i = 0; i < headSlots.length; i++) {
                if (headSlots[i] == null) {
                    try {
                        headSlots[i] = env.createSession(headModelBytes, sessionOpts);
                    } catch (OrtException ignored) {
                    }
                }
            }
        }
    }

    public String recognizeTensor(float[][][][] nchw, int maxNewTokens) throws OrtException {
        OrtSession head = borrowHead();
        try {
            long[] imageShape = {nchw.length, nchw[0].length, nchw[0][0].length, nchw[0][0][0].length};
            float[] flat = flatten(nchw);
            try (OnnxTensor image = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), imageShape);
                 OrtSession.Result encResult = backbone.run(Map.of(backboneInputName(), image))) {
                Object encVal = encResult.get(0).getValue();
                if (!(encVal instanceof float[][][] encoder)) {
                    throw new IllegalStateException("unexpected encoder type: " + encVal.getClass()
                            + " imageShape=" + java.util.Arrays.toString(imageShape));
                }
                long[] encShape = {encoder.length, encoder[0].length, encoder[0][0].length};
                float[] encFlat = flatten3(encoder);
                List<Long> tokens = new ArrayList<>();
                tokens.add((long) BOS);
                try (OnnxTensor enc = OnnxTensor.createTensor(env, FloatBuffer.wrap(encFlat), encShape)) {
                    for (int step = 0; step < maxNewTokens; step++) {
                        long next;
                        try {
                            next = nextToken(head, enc, tokens);
                        } catch (OrtException e) {
                            throw new OrtException("head failed at step=" + step
                                    + " toks=" + tokens.size() + ": " + e.getMessage());
                        }
                        tokens.add(next);
                        if (next == EOS) {
                            break;
                        }
                    }
                }
                long[] ids = new long[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) {
                    ids[i] = tokens.get(i);
                }
                return postProcess(decode(ids));
            }
        } finally {
            try {
                head.close();
            } catch (Exception ignored) {
            }
            refillEmptyHeadSlots();
        }
    }

    private long nextToken(OrtSession head, OnnxTensor enc, List<Long> tokens) throws OrtException {
        long[] ids = new long[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            ids[i] = tokens.get(i);
        }
        long[] decoderShape = {1, ids.length};
        try (OnnxTensor decoder = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), decoderShape)) {
            Map<String, OnnxTensor> feed = new HashMap<>();
            for (String name : head.getInputInfo().keySet()) {
                String lower = name.toLowerCase();
                if (lower.contains("decoder") || lower.contains("input")) {
                    feed.put(name, decoder);
                } else {
                    feed.put(name, enc);
                }
            }
            try (OrtSession.Result result = head.run(feed)) {
                Object value = result.get(0).getValue();
                float[] logits;
                if (value instanceof float[][] arr2) {
                    logits = arr2[0];
                } else if (value instanceof float[][][] arr3) {
                    float[][] seq = arr3[0];
                    logits = seq[seq.length - 1];
                } else if (value instanceof float[] arr1) {
                    logits = arr1;
                } else {
                    throw new IllegalStateException("unexpected logits type: " + value.getClass());
                }
                int best = 0;
                float bestV = logits[0];
                for (int i = 1; i < logits.length; i++) {
                    if (logits[i] > bestV) {
                        bestV = logits[i];
                        best = i;
                    }
                }
                return best;
            }
        }
    }

    private String backboneInputName() throws OrtException {
        return backbone.getInputInfo().keySet().iterator().next();
    }

    /** 对齐 UniMERNetImgDecode(eval) + UniMERNetTestTransform + LatexImageFormat + 3ch repeat. */
    static float[][][][] preprocess(BufferedImage src) {
        if (src == null) {
            throw new IllegalArgumentException("image is null");
        }
        BufferedImage rgb = toRgb(src);
        BufferedImage cropped = cropMargin(rgb);
        BufferedImage sized = resizeKeepAspectPad(cropped, INPUT_SIZE);
        int h = sized.getHeight();
        int w = sized.getWidth();
        float[][] gray = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = sized.getRGB(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                float v = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                gray[y][x] = (v - NORM_MEAN) / NORM_STD;
            }
        }
        int padH = ((h + 15) / 16) * 16;
        int padW = ((w + 15) / 16) * 16;
        float[][] padded = new float[padH][padW];
        for (int y = 0; y < padH; y++) {
            for (int x = 0; x < padW; x++) {
                padded[y][x] = (y < h && x < w) ? gray[y][x] : 1f;
            }
        }
        float[][][][] out = new float[1][3][padH][padW];
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < padH; y++) {
                System.arraycopy(padded[y], 0, out[0][c][y], 0, padW);
            }
        }
        return out;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    /** 对齐 UniMERNetImgDecode.crop_margin */
    static BufferedImage cropMargin(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int min = 255;
        int max = 0;
        int[] gray = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int v = (r + g + b) / 3;
                gray[y * w + x] = v;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        if (max == min) {
            return img;
        }
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float norm = (gray[y * w + x] - min) * 255f / (max - min);
                if (norm < 200f) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return img;
        }
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** shorter-side→size，再 thumbnail 到 size×size，居中白边 padding。 */
    static BufferedImage resizeKeepAspectPad(BufferedImage img, int size) {
        int w = img.getWidth();
        int h = img.getHeight();
        int shortSide = Math.min(w, h);
        int longSide = Math.max(w, h);
        int newShort = size;
        int newLong = Math.max(1, (int) (size * (longSide / (double) shortSide)));
        int nw = w <= h ? newShort : newLong;
        int nh = w <= h ? newLong : newShort;
        // thumbnail：最长边不超过 size
        if (nw > size || nh > size) {
            double scale = Math.min(size / (double) nw, size / (double) nh);
            nw = Math.max(1, (int) (nw * scale));
            nh = Math.max(1, (int) (nh * scale));
        }
        // 一步缩放到目标尺寸再垫黑边（对齐 PIL BILINEAR）
        BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D cg = canvas.createGraphics();
        cg.setColor(java.awt.Color.BLACK);
        cg.fillRect(0, 0, size, size);
        cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int padL = (size - nw) / 2;
        int padT = (size - nh) / 2;
        cg.drawImage(img, padL, padT, nw, nh, null);
        cg.dispose();
        return canvas;
    }

    public String decode(long[] ids) {
        return decodeIds(id2token, unicodeToByte, ids);
    }

    /** 仅词表解码（不加载 ONNX），方便单测。 */
    public static String decodeOnly(Path tokenizerJson, long[] ids) throws IOException {
        return decodeIds(loadVocab(tokenizerJson), buildUnicodeToByte(), ids);
    }

    private static String decodeIds(String[] id2token, Map<Character, Integer> unicodeToByte, long[] ids) {
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            int i = (int) id;
            if (i == BOS || i == EOS || i == PAD || i == UNK) {
                continue;
            }
            if (i < 0 || i >= id2token.length) {
                continue;
            }
            String tok = id2token[i];
            if (tok != null) {
                sb.append(tok);
            }
        }
        String mapped = sb.toString();
        byte[] raw = new byte[mapped.length()];
        for (int i = 0; i < mapped.length(); i++) {
            Integer b = unicodeToByte.get(mapped.charAt(i));
            raw[i] = (byte) (b == null ? (int) mapped.charAt(i) : b);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /** 精简版 UniMERNetDecode.normalize（去多余空格）。 */
    public static String postProcess(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Pattern textReg = Pattern.compile("(\\\\(operatorname|mathrm|text|mathbf)\\s?\\*? \\{.*?\\})");
        Matcher m = textReg.matcher(text);
        List<String> names = new ArrayList<>();
        StringBuffer tmp = new StringBuffer();
        while (m.find()) {
            names.add(m.group(1).replace(" ", ""));
            m.appendReplacement(tmp, "___NAME___");
        }
        m.appendTail(tmp);
        String s = tmp.toString();
        for (String name : names) {
            s = s.replaceFirst("___NAME___", Matcher.quoteReplacement(name));
        }
        String letter = "[a-zA-Z]";
        String noletter = "[\\W_^\\d]";
        String news = s;
        while (true) {
            s = news;
            news = s.replaceAll("(?!\\\\ )(" + noletter + ")\\s+?(" + noletter + ")", "$1$2");
            news = news.replaceAll("(?!\\\\ )(" + noletter + ")\\s+?(" + letter + ")", "$1$2");
            news = news.replaceAll("(" + letter + ")\\s+?(" + noletter + ")", "$1$2");
            if (news.equals(s)) {
                break;
            }
        }
        return news;
    }

    private static String[] loadVocab(Path tokenizerJson) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(tokenizerJson.toFile());
        JsonNode vocab = root.path("model").path("vocab");
        if (!vocab.isObject()) {
            throw new IOException("tokenizer.json missing model.vocab");
        }
        int maxId = -1;
        Iterator<Map.Entry<String, JsonNode>> it = vocab.fields();
        Map<Integer, String> map = new HashMap<>();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            int id = e.getValue().asInt();
            map.put(id, e.getKey());
            if (id > maxId) {
                maxId = id;
            }
        }
        String[] arr = new String[maxId + 1];
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            arr[e.getKey()] = e.getValue();
        }
        return arr;
    }

    /** GPT-2 bytes_to_unicode 的逆映射。 */
    private static Map<Character, Integer> buildUnicodeToByte() {
        List<Integer> bs = new ArrayList<>();
        for (int i = '!'; i <= '~'; i++) bs.add(i);
        for (int i = '¡'; i <= '¬'; i++) bs.add(i);
        for (int i = '®'; i <= 'ÿ'; i++) bs.add(i);
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        Map<Character, Integer> u2b = new HashMap<>();
        for (int i = 0; i < bs.size(); i++) {
            u2b.put((char) cs.get(i).intValue(), bs.get(i));
        }
        return u2b;
    }

    private static float[] flatten(float[][][][] t) {
        int b = t.length;
        int c = t[0].length;
        int h = t[0][0].length;
        int w = t[0][0][0].length;
        float[] out = new float[b * c * h * w];
        int i = 0;
        for (int bi = 0; bi < b; bi++) {
            for (int ci = 0; ci < c; ci++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        out[i++] = t[bi][ci][y][x];
                    }
                }
            }
        }
        return out;
    }

    private static float[] flatten3(float[][][] t) {
        int a = t.length;
        int b = t[0].length;
        int c = t[0][0].length;
        float[] out = new float[a * b * c];
        int i = 0;
        for (int x = 0; x < a; x++) {
            for (int y = 0; y < b; y++) {
                for (int z = 0; z < c; z++) {
                    out[i++] = t[x][y][z];
                }
            }
        }
        return out;
    }

    public static float[][][][] loadNchwF32(Path raw, int n, int c, int h, int w) throws IOException {
        byte[] bytes = Files.readAllBytes(raw);
        if (bytes.length != (long) n * c * h * w * 4) {
            throw new IOException("unexpected raw size: " + bytes.length);
        }
        FloatBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[][][][] out = new float[n][c][h][w];
        for (int ni = 0; ni < n; ni++) {
            for (int ci = 0; ci < c; ci++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        out[ni][ci][y][x] = buf.get();
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        synchronized (headLock) {
            for (int i = 0; i < headSlots.length; i++) {
                try {
                    if (headSlots[i] != null) {
                        headSlots[i].close();
                        headSlots[i] = null;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        try {
            backbone.close();
        } catch (Exception ignored) {
        }
    }
}
