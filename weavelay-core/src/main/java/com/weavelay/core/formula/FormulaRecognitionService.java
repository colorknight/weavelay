package com.weavelay.core.formula;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公式识别：裁剪图 → LaTeX，仅进程内 ONNX（{@link #setLocalRecognizer}）。
 */
public final class FormulaRecognitionService {

    /** 本地 ONNX：默认一路；质量不够再补白边。 */
    private static final int[] LOCAL_PAD_LEFT_PX = {0, 12};

    private volatile FormulaImageRecognizer localRecognizer;

    public FormulaRecognitionService() {
    }

    /** 设置本地公式识别（如 PP-FormulaNet ONNX）。 */
    public void setLocalRecognizer(FormulaImageRecognizer recognizer) {
        this.localRecognizer = recognizer;
    }

    public boolean hasLocalRecognizer() {
        return localRecognizer != null;
    }

    /**
     * 识别单张已裁剪的公式 PNG。
     * <p>多路白边送检后挑选结果，缓解⌀被认成 {@code \\otimes} 等抖动。
     *
     * @return LaTeX；未挂本地引擎或失败时返回空串
     */
    public String recognizeSingle(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        if (localRecognizer == null) {
            return "";
        }
        List<String> candidates = new ArrayList<>();
        for (int leftPad : LOCAL_PAD_LEFT_PX) {
            byte[] payload = imageBytes;
            if (leftPad > 0) {
                byte[] padded = padWhite(imageBytes, leftPad, Math.max(4, leftPad / 2), 6, 6);
                if (padded != null) {
                    payload = padded;
                }
            }
            String latex = recognizeOnce(payload);
            if (latex != null && !latex.isBlank()) {
                String n = DiameterLatexNormalizer.normalize(latex.trim());
                candidates.add(n);
                if (looksLikeDiameterLatex(n) && DiameterLatexNormalizer.hasArabicDigit(n)) {
                    break;
                }
                if (leftPad == 0 && DiameterLatexNormalizer.hasArabicDigit(n)
                        && !DiameterLatexNormalizer.needsDigitSalvage(n)) {
                    break;
                }
            }
        }
        return DiameterLatexNormalizer.normalize(pickStableLatex(candidates));
    }

    private String recognizeOnce(byte[] imageBytes) {
        FormulaImageRecognizer local = localRecognizer;
        if (local == null) {
            return "";
        }
        try {
            String latex = local.recognizeImage(imageBytes);
            return latex == null ? "" : latex;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 优先选含直径常用符号的结果；否则多数票；再否则第一条。
     */
    static String pickStableLatex(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        for (String c : candidates) {
            if (looksLikeDiameterLatex(c) && DiameterLatexNormalizer.hasArabicDigit(c)) {
                return c;
            }
        }
        for (String c : candidates) {
            if (looksLikeDiameterLatex(c)) {
                return c;
            }
        }
        for (String c : candidates) {
            if (DiameterLatexNormalizer.hasArabicDigit(c)) {
                return c;
            }
        }
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String c : candidates) {
            freq.merge(c, 1, Integer::sum);
        }
        String best = candidates.get(0);
        int bestN = 0;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static boolean looksLikeDiameterLatex(String latex) {
        if (latex == null) {
            return false;
        }
        String s = latex.toLowerCase();
        return s.contains("varnothing")
                || s.contains("emptyset")
                || s.contains("diameter")
                || s.contains("\\oslash");
    }

    /** 四周白边；失败返回 null。 */
    static byte[] padWhite(byte[] png, int left, int right, int top, int bottom) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) {
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            int l = Math.max(0, left);
            int r = Math.max(0, right);
            int t = Math.max(0, top);
            int b = Math.max(0, bottom);
            BufferedImage out = new BufferedImage(w + l + r, h + t + b, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, l, t, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "PNG", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
