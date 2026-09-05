package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 混合单元格：公式区 → LaTeX，其余 → OCR，再按坐标拼接。
 * <ol>
 *   <li>按 detect 框涂白；仅极小外扩抹贴边残笔（左右邻字「车/验/至/长」很近）</li>
 *   <li>相邻公式之间的缝<strong>不涂死</strong>，让 OCR 自己读夹缝标点（逗号/句号）</li>
 *   <li>OCR 涂白后的图；字中心落在涂白区内的 token 丢掉（标点除外）</li>
 *   <li>不发明标点：绝不按墨迹硬补逗号（句号会被误判）</li>
 * </ol>
 */
public final class FormulaMixedComposer {

    /**
     * 涂白外扩：墨迹扩框已吞⌀；这里只留小缝抹贴边残笔。
     * 左缘略大于右：⌀ 左撇常漏成 OCR 的 e/c/o。
     */
    static final float MASK_LEFT_OUTSET_H_RATIO = 0.08f;
    static final float MASK_RIGHT_OUTSET_H_RATIO = 0.08f;
    static final float MASK_VERT_OUTSET_H_RATIO = 0.10f;
    static final int MASK_LEFT_OUTSET_MAX_PX = 7;
    static final int MASK_RIGHT_OUTSET_MAX_PX = 8;

    private FormulaMixedComposer() {}

    public record FormulaPiece(int x, int y, int w, int h, String latex, boolean display) {
        public int endX() { return x + w; }
        public int endY() { return y + h; }
        public double centerY() { return y + h / 2.0; }
        public double centerX() { return x + w / 2.0; }
    }

    public static String compose(
            byte[] cellPng,
            List<FormulaPiece> formulas,
            RapidOcrService ocr) {
        if (cellPng == null || cellPng.length == 0) {
            return "";
        }
        BufferedImage cellImg;
        try {
            cellImg = ImageIO.read(new ByteArrayInputStream(cellPng));
        } catch (Exception e) {
            return "";
        }
        return compose(cellImg, formulas, ocr);
    }

    /**
     * 已解码单元格图直传：涂白后 OCR，免 PNG 编解码往返（识别参数不变）。
     */
    public static String compose(
            BufferedImage cellImg,
            List<FormulaPiece> formulas,
            RapidOcrService ocr) {
        List<FormulaPiece> validFormulas = validFormulas(formulas);
        if (cellImg == null || validFormulas.isEmpty()) {
            return validFormulas.isEmpty() ? "" : joinFormulasOnly(validFormulas);
        }
        return composeMasked(cellImg, validFormulas, ocr);
    }

    /**
     * 优先用表 OCR 明文与公式交叉拼接，省掉 {@code formula-mixed} 二次 OCR；
     * 明文不够用时再回退全路径。
     */
    public static String composePreferringPlain(
            BufferedImage cellImg,
            byte[] cellBytes,
            List<FormulaPiece> formulas,
            String plainText,
            RapidOcrService ocr) {
        List<FormulaPiece> validFormulas = validFormulas(formulas);
        if (validFormulas.isEmpty()) {
            return "";
        }
        String formulasOnly = joinFormulasOnly(validFormulas);
        String fromPlain = interleavePlainWithFormulas(plainText, validFormulas);
        if (fromPlain != null && !fromPlain.isBlank()) {
            return mergePreservingPlainTail(plainText, fromPlain);
        }
        String merged = mergePreservingPlainTail(plainText, formulasOnly);
        // 无工序动词/夹缝字：纯公式或仅续写说明 → 不必再 OCR
        if (!hasInterleavedProcessText(plainText)) {
            if (cellImg != null) {
                try {
                    BufferedImage masked = maskFormulaRegions(cellImg, validFormulas);
                    if (masked != null && !isNearlyBlankAfterMask(masked)
                            && (plainText == null || plainText.isBlank())) {
                        // 明文空但涂白后仍有墨 → 可能漏字，走混合 OCR
                        return composeMasked(cellImg, validFormulas, ocr);
                    }
                } catch (Exception ignored) {
                }
            }
            return merged;
        }
        // 有「车/至」等但交叉拼接失败 → 混合 OCR
        if (cellImg != null) {
            return composeMasked(cellImg, validFormulas, ocr);
        }
        if (cellBytes != null && cellBytes.length > 0) {
            return compose(cellBytes, validFormulas, ocr);
        }
        return merged;
    }

    private static List<FormulaPiece> validFormulas(List<FormulaPiece> formulas) {
        List<FormulaPiece> valid = new ArrayList<>();
        if (formulas == null) {
            return valid;
        }
        for (FormulaPiece f : formulas) {
            if (f == null || f.w() <= 1 || f.h() <= 1) {
                continue;
            }
            if (f.latex() == null || f.latex().isBlank()) {
                continue;
            }
            valid.add(f);
        }
        return valid;
    }

    private static String composeMasked(
            BufferedImage cellImg, List<FormulaPiece> validFormulas, RapidOcrService ocr) {
        BufferedImage masked;
        try {
            masked = maskFormulaRegions(cellImg, validFormulas);
        } catch (Exception e) {
            return joinFormulasOnly(validFormulas);
        }
        if (masked == null) {
            return joinFormulasOnly(validFormulas);
        }
        // 纯公式格：涂白后几乎无残余墨迹，跳过二次 OCR
        if (isNearlyBlankAfterMask(masked)) {
            return joinFormulasOnly(validFormulas);
        }

        List<OcrRow> textRows;
        try {
            RapidOcrService.prepareNativeRuntime();
            OcrParamSettings withChars = ocr.getParams();
            withChars.setReturnWordBox(true);
            textRows = ocr.recognizeBufferedImage(masked, "formula-mixed", withChars);
        } catch (Exception e) {
            return joinFormulasOnly(validFormulas);
        }

        List<OcrRow> keptTexts = keepResidualTexts(textRows, validFormulas);
        keptTexts = dropDuplicateDimensionResidues(keptTexts, validFormulas);
        return stitch(keptTexts, validFormulas)
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    /** 表 OCR 里夹在数字间的工序字：车/至/处… */
    static boolean hasInterleavedProcessText(String plain) {
        if (plain == null || plain.isBlank()) {
            return false;
        }
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c == '车' || c == '铣' || c == '磨' || c == '钻' || c == '镗'
                    || c == '至' || c == '处' || c == '验' || c == '刨' || c == '插') {
                return true;
            }
        }
        return false;
    }

    /**
     * 用表 OCR 结构（汉字段 + 数字段）把公式嵌回去，避免再跑字框 OCR。
     * 例：车148+0至134 → 车$...$至$...$
     *
     * @return 成功拼接的串；结构对不上则 null
     */
    static String interleavePlainWithFormulas(String plain, List<FormulaPiece> formulas) {
        if (plain == null || plain.isBlank() || formulas == null || formulas.isEmpty()) {
            return null;
        }
        List<String> parts = splitPlainProcessParts(plain);
        if (parts.isEmpty()) {
            return null;
        }
        int digitRuns = 0;
        for (String p : parts) {
            if (isDigitishRun(p)) {
                digitRuns++;
            }
        }
        if (digitRuns == 0) {
            return null;
        }
        // 数字段数与公式数需大致匹配（允许公式略多：末尾公差）
        if (digitRuns > formulas.size() || formulas.size() - digitRuns > 1) {
            return null;
        }
        List<FormulaPiece> ordered = sortFormulasReadingOrder(formulas);
        StringBuilder sb = new StringBuilder();
        int fi = 0;
        for (String p : parts) {
            if (isDigitishRun(p)) {
                if (fi >= ordered.size()) {
                    return null;
                }
                sb.append(formatFormula(ordered.get(fi++)));
            } else {
                sb.append(p);
            }
        }
        while (fi < ordered.size()) {
            sb.append(formatFormula(ordered.get(fi++)));
        }
        return sb.toString().trim();
    }

    /**
     * 阅读顺序：先按 Y 粗排，再用与 {@link #stitch} 相同的容差分行，行内按左缘 X。
     * <p>
     * 不能只按 {@code centerY}：带上标公差的 Ø148 框更高，中心偏下；矮的 Ø11
     * 中心更靠上，会被全局 Y 排序插到同行左侧直径前面。
     */
    static List<FormulaPiece> sortFormulasReadingOrder(List<FormulaPiece> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return List.of();
        }
        List<FormulaPiece> byY = new ArrayList<>(formulas);
        byY.sort(Comparator
                .comparingDouble(FormulaPiece::centerY)
                .thenComparingInt(FormulaPiece::x));

        double yTol = 40.0;
        for (FormulaPiece f : formulas) {
            yTol = Math.max(yTol, f.h() * 0.8);
        }

        List<FormulaPiece> ordered = new ArrayList<>(byY.size());
        List<FormulaPiece> current = new ArrayList<>();
        double currentY = Double.NaN;
        for (FormulaPiece f : byY) {
            if (current.isEmpty() || Double.isNaN(currentY)
                    || Math.abs(f.centerY() - currentY) < yTol) {
                current.add(f);
                currentY = Double.isNaN(currentY) ? f.centerY() : (currentY + f.centerY()) / 2.0;
            } else {
                current.sort(Comparator.comparingInt(FormulaPiece::x)
                        .thenComparingDouble(FormulaPiece::centerX));
                ordered.addAll(current);
                current = new ArrayList<>();
                current.add(f);
                currentY = f.centerY();
            }
        }
        if (!current.isEmpty()) {
            current.sort(Comparator.comparingInt(FormulaPiece::x)
                    .thenComparingDouble(FormulaPiece::centerX));
            ordered.addAll(current);
        }
        return ordered;
    }

    /** 拆成汉字/标点段与数字公差段。 */
    static List<String> splitPlainProcessParts(String plain) {
        List<String> parts = new ArrayList<>();
        if (plain == null) {
            return parts;
        }
        String s = plain.replace('\r', '\n').trim();
        if (s.isEmpty()) {
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        Boolean digitMode = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                digitMode = null;
                continue;
            }
            boolean dig = isDigitishChar(c);
            if (digitMode == null) {
                digitMode = dig;
                cur.append(c);
            } else if (digitMode == dig) {
                cur.append(c);
            } else {
                parts.add(cur.toString());
                cur.setLength(0);
                cur.append(c);
                digitMode = dig;
            }
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        // 「至77处」里的 77、「11长6」整段，不要拆成多余公式槽
        return coalesceProcessConnectors(parts);
    }

    /**
     * 合并工序夹缝：至+数字+处 → 整段保留；数字+长+数字 → 一个尺寸槽。
     * 否则「至77处」的 77 会多占一个公式位，交叉拼接失败，中段中文整段丢掉。
     */
    static List<String> coalesceProcessConnectors(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return parts == null ? List.of() : parts;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); ) {
            String a = parts.get(i);
            String b = i + 1 < parts.size() ? parts.get(i + 1) : null;
            String c = i + 2 < parts.size() ? parts.get(i + 2) : null;
            // 至 + 77 + 处/处，
            if ("至".equals(a) && isPureNumberRun(b) && c != null && c.startsWith("处")) {
                out.add(a + b + c);
                i += 3;
                continue;
            }
            // 已拆成「至77」+「处…」
            if (a != null && a.startsWith("至") && isPureNumberRun(a.substring(1))
                    && b != null && b.startsWith("处")) {
                out.add(a + b);
                i += 2;
                continue;
            }
            // 11 + 长 + 6 → 一个尺寸段（对应 ∅11长6）
            if (isPureNumberRun(a) && "长".equals(b) && isPureNumberRun(c)) {
                out.add(a + b + c);
                i += 3;
                continue;
            }
            // 11长 + 6
            if (a != null && a.endsWith("长") && isPureNumberRun(a.substring(0, a.length() - 1))
                    && isPureNumberRun(b)) {
                out.add(a + b);
                i += 2;
                continue;
            }
            out.add(a);
            i++;
        }
        return out;
    }

    private static boolean isDigitishChar(char c) {
        return (c >= '0' && c <= '9')
                || c == '+' || c == '-' || c == '±' || c == '.' || c == ','
                || c == '⌀' || c == '∅' || c == 'ø' || c == 'Ø'
                || c == '_' || c == '^' || c == '{' || c == '}' || c == '\\';
    }

    private static boolean isDigitishRun(String p) {
        if (p == null || p.isBlank()) {
            return false;
        }
        // 工序夹缝（至77处 / 处，）不是公式槽
        if (isProcessConnectorOnly(p)) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits > 0;
    }

    /** 纯数字串（可带空白），不含公差符号。 */
    private static boolean isPureNumberRun(String p) {
        if (p == null || p.isBlank()) {
            return false;
        }
        boolean any = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c >= '0' && c <= '9') {
                any = true;
            } else if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return any;
    }

    /**
     * 工序夹缝/定位语：至77处、至77处，——必须保留，不能当尺寸残片丢掉。
     */
    static boolean isProcessConnectorOnly(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String s = line.trim();
        // 至77处 / 至77处，/ ,
        if (s.matches("至\\d+处[，,。.\\s]*")) {
            return true;
        }
        // 单独「处，」或逗号
        if (s.matches("处[，,。.\\s]*") || s.matches("[，,。.]+")) {
            return true;
        }
        return false;
    }

    /** 丢掉与公式结果同义的尺寸 OCR 残片，避免「公式 + 车148+0…」双份。 */
    static List<OcrRow> dropDuplicateDimensionResidues(List<OcrRow> texts, List<FormulaPiece> formulas) {
        if (texts == null || texts.isEmpty()) {
            return texts == null ? List.of() : texts;
        }
        StringBuilder formulaPlain = new StringBuilder();
        for (FormulaPiece f : formulas) {
            if (f != null && f.latex() != null) {
                formulaPlain.append(f.latex());
            }
        }
        String fp = collapseForDupCheck(formulaPlain.toString());
        List<OcrRow> out = new ArrayList<>();
        for (OcrRow row : texts) {
            if (row == null || row.getFeature() == null) {
                continue;
            }
            String feat = row.getFeature().trim();
            if (feat.isEmpty()) {
                continue;
            }
            if (isDimensionResidueLine(feat) || isNearDuplicateOf(fp, feat)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 公式合成后若丢掉原文里纯中文续行（如「完成R2,全长≥57。」），补回。
     * 不会补回同义的尺寸 OCR 残片（如「车148+0至77处」）。
     */
    public static String mergePreservingPlainTail(String original, String mixed) {
        if (mixed == null || mixed.isBlank()) {
            return original == null ? "" : original;
        }
        if (original == null || original.isBlank()) {
            return mixed;
        }
        String out = mixed.trim();
        String mixedPlain = collapseForDupCheck(mixed);
        for (String raw : original.split("\\R")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.indexOf('$') >= 0 || line.indexOf('\\') >= 0) {
                continue;
            }
            if (line.indexOf('⌀') >= 0 || line.indexOf('∅') >= 0 || line.indexOf('ø') >= 0 || line.indexOf('Ø') >= 0) {
                continue;
            }
            if (containsLine(out, line)) {
                continue;
            }
            // 尺寸行残片：带数字的「车…至…」等，已由公式结果覆盖
            if (isDimensionResidueLine(line) || isNearDuplicateOf(mixedPlain, line)) {
                continue;
            }
            if (looksLikePlainContinuationLine(line)) {
                out = out + "\n" + line;
            }
        }
        return out;
    }

    private static boolean containsLine(String hay, String line) {
        for (String raw : hay.split("\\R")) {
            if (line.equals(raw.trim())) {
                return true;
            }
        }
        return hay.contains(line);
    }

    /** 仅续写性说明，不要尺寸描述本行。 */
    private static boolean looksLikePlainContinuationLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (isDimensionResidueLine(line)) {
            return false;
        }
        return line.contains("完成") || line.contains("全长") || line.contains("注意")
                || line.contains("去毛刺") || line.contains("锐边");
    }

    /** OCR 未吃到 ⌀/公差时的同义尺寸行：车148+0至… / 134+0 / 11长6 */
    static boolean isDimensionResidueLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String s = line.trim();
        // 续写说明不是尺寸残片
        if (s.contains("完成") || s.contains("全长") || s.contains("注意")
                || s.contains("去毛刺") || s.contains("锐边")) {
            return false;
        }
        // 「至77处」是夹缝工序字，不是整行尺寸残片
        if (isProcessConnectorOnly(s)) {
            return false;
        }
        if (!s.chars().anyMatch(Character::isDigit)) {
            return false;
        }
        if (s.contains("至") || s.contains("处")
                || s.indexOf('+') >= 0 || s.indexOf('±') >= 0) {
            return true;
        }
        // 「11长6」类：数字+长+数字
        if (s.matches(".*\\d\\s*长\\s*\\d.*")) {
            return true;
        }
        int digits = 0;
        int han = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                han++;
            }
        }
        return digits >= 2 && han > 0 && digits >= han;
    }

    public static boolean isNearDuplicateOf(String mixedPlain, String line) {
        String a = collapseForDupCheck(mixedPlain);
        String b = collapseForDupCheck(line);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        // 共享较长的数字串
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{2,}").matcher(b);
        int hits = 0;
        while (m.find()) {
            if (a.contains(m.group())) {
                hits++;
            }
        }
        return hits >= 2;
    }

    /** 去掉公式标记/空白，便于判重。 */
    static String collapseForDupCheck(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("$", "")
                .replace("\\varnothing", "")
                .replace("\\emptyset", "")
                .replace("⌀", "")
                .replace("∅", "")
                .replace("ø", "")
                .replace("Ø", "")
                .replaceAll("_\\{[^}]*\\}", "")
                .replaceAll("\\^\\{[^}]*\\}", "")
                .replaceAll("[_^{}+\\-±·,，。\\s]", "")
                .trim();
    }

    private static String joinFormulasOnly(List<FormulaPiece> formulas) {
        StringBuilder sb = new StringBuilder();
        List<FormulaPiece> ordered = sortFormulasReadingOrder(formulas);
        for (FormulaPiece f : ordered) {
            if (sb.length() > 0) {
                sb.append(f.display() ? "\n" : "");
            }
            sb.append(formatFormula(f));
        }
        return sb.toString();
    }

    /**
     * 涂白后残余深色像素占比过低 → 视为纯公式格，无需再 OCR。
     * 阈值偏低，避免「车/至」等细字被误判跳过。
     */
    static final float PURE_FORMULA_DARK_RATIO_MAX = 0.006f;

    static boolean isNearlyBlankAfterMask(BufferedImage masked) {
        if (masked == null || masked.getWidth() <= 0 || masked.getHeight() <= 0) {
            return true;
        }
        int w = masked.getWidth();
        int h = masked.getHeight();
        long dark = 0;
        long total = (long) w * h;
        // 步进抽样，大格也足够稳
        int step = Math.max(1, Math.min(w, h) / 120);
        long samples = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = masked.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (r * 30 + g * 59 + b * 11) / 100;
                samples++;
                if (lum < 200) {
                    dark++;
                }
            }
        }
        if (samples == 0) {
            return true;
        }
        return (dark / (double) samples) <= PURE_FORMULA_DARK_RATIO_MAX;
    }

    /** 拷贝后涂白公式区，不改动原图。 */
    private static BufferedImage maskFormulaRegions(
            BufferedImage cellImg, List<FormulaPiece> formulas) {
        if (cellImg == null) {
            return null;
        }
        int type = cellImg.getType() != BufferedImage.TYPE_CUSTOM
                ? cellImg.getType()
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage img = new BufferedImage(cellImg.getWidth(), cellImg.getHeight(), type);
        Graphics2D g = img.createGraphics();
        try {
            g.drawImage(cellImg, 0, 0, null);
            g.setColor(Color.WHITE);
            for (FormulaPiece f : formulas) {
                int[] caps = adjacentFormulaOutsetCaps(f, formulas);
                int[] r = maskRect(f, img.getWidth(), img.getHeight(), caps[0], caps[1]);
                if (r != null) {
                    g.fillRect(r[0], r[1], r[2], r[3]);
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * 相对同行相邻公式：左右外扩不超过缝宽一半，避免把夹缝里的逗号涂掉。
     *
     * @return {@code int[]{maxLeftOut, maxRightOut}}，无邻居时为很大值
     */
    static int[] adjacentFormulaOutsetCaps(FormulaPiece f, List<FormulaPiece> formulas) {
        int maxLeft = Integer.MAX_VALUE / 4;
        int maxRight = Integer.MAX_VALUE / 4;
        for (FormulaPiece o : formulas) {
            if (o == null || o == f) {
                continue;
            }
            int yOverlap = Math.min(f.endY(), o.endY()) - Math.max(f.y(), o.y());
            if (yOverlap < Math.min(f.h(), o.h()) * 0.35) {
                continue;
            }
            if (o.x() >= f.endX()) {
                int gap = o.x() - f.endX();
                if (gap > 0) {
                    maxRight = Math.min(maxRight, Math.max(0, gap / 2 - 1));
                } else {
                    maxRight = 0;
                }
            } else if (o.endX() <= f.x()) {
                int gap = f.x() - o.endX();
                if (gap > 0) {
                    maxLeft = Math.min(maxLeft, Math.max(0, gap / 2 - 1));
                } else {
                    maxLeft = 0;
                }
            }
        }
        return new int[]{maxLeft, maxRight};
    }

    /**
     * 涂白矩形：相对 detect 框按字高比例外扩；受相邻公式缝宽上限约束。
     *
     * @return {@code int[]{x,y,w,h}} 或 null
     */
    static int[] maskRect(FormulaPiece f, int imgW, int imgH, int maxLeftOut, int maxRightOut) {
        int leftOut = Math.min(
                sideOutset(f.h(), MASK_LEFT_OUTSET_H_RATIO, 1, MASK_LEFT_OUTSET_MAX_PX),
                Math.max(0, maxLeftOut));
        int rightOut = Math.min(
                sideOutset(f.h(), MASK_RIGHT_OUTSET_H_RATIO, 1, MASK_RIGHT_OUTSET_MAX_PX),
                Math.max(0, maxRightOut));
        int vertOut = Math.max(1, Math.round(f.h() * MASK_VERT_OUTSET_H_RATIO));
        int x1 = f.x() - leftOut;
        int y1 = f.y() - vertOut;
        int x2 = f.endX() + rightOut;
        int y2 = f.endY() + vertOut;
        x1 = Math.max(0, x1);
        y1 = Math.max(0, y1);
        x2 = Math.min(imgW, x2);
        y2 = Math.min(imgH, y2);
        if (x2 <= x1 || y2 <= y1) {
            return null;
        }
        return new int[]{x1, y1, x2 - x1, y2 - y1};
    }

    private static int sideOutset(int formulaH, float ratio, int minPx, int maxPx) {
        int v = Math.round(formulaH * ratio);
        return Math.max(minPx, Math.min(maxPx, v));
    }

    /**
     * 字中心落在涂白区（detect + 同比例外扩）内则丢；无字框时按行重叠率丢。
     */
    static List<OcrRow> keepResidualTexts(List<OcrRow> textRows, List<FormulaPiece> formulas) {
        List<OcrRow> kept = new ArrayList<>();
        if (textRows == null) {
            return kept;
        }
        for (OcrRow row : textRows) {
            if (row == null || row.getFeature() == null || row.getFeature().isBlank()) {
                continue;
            }
            if (!row.hasBbox()) {
                continue;
            }
            OcrTokenMeta meta = OcrTokenMeta.fromMetas(row.getMetas());
            if (meta == null || meta.getTokens().isEmpty()) {
                if (!rowOverlapsFormula(row, formulas, 0.45)) {
                    String cleaned = stripResidueFromPlainFeature(row.getFeature(), row, formulas);
                    if (cleaned != null && !cleaned.isBlank()) {
                        kept.add(new OcrRow(
                                row.getStreamName(),
                                cleaned,
                                row.getProb(),
                                row.getStartX(),
                                row.getStartY(),
                                row.getEndX(),
                                row.getEndY(),
                                row.getMetas()));
                    }
                }
                continue;
            }
            List<OcrTokenBox> tokens = new ArrayList<>(meta.getTokens());
            tokens.sort(Comparator.comparingDouble(OcrTokenBox::getStartX));
            List<OcrTokenBox> run = new ArrayList<>();
            for (OcrTokenBox tok : tokens) {
                if (tok == null || tok.getText() == null || tok.getText().isBlank()) {
                    continue;
                }
                OcrTokenBox cleaned = stripFormulaEdgeResidueChars(tok, formulas);
                if (cleaned == null) {
                    flushTokenRun(row, run, kept);
                    run.clear();
                    continue;
                }
                if (isKeepPunctuation(cleaned.getText())) {
                    run.add(cleaned);
                    continue;
                }
                if (tokenCenterInMaskZone(cleaned, formulas) || isFormulaEdgeResidue(cleaned, formulas)) {
                    flushTokenRun(row, run, kept);
                    run.clear();
                } else {
                    run.add(cleaned);
                }
            }
            flushTokenRun(row, run, kept);
        }
        return kept;
    }

    private static void flushTokenRun(OcrRow source, List<OcrTokenBox> run, List<OcrRow> out) {
        if (run == null || run.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double confSum = 0;
        for (OcrTokenBox t : run) {
            text.append(t.getText());
            confSum += t.getConfidence();
            minX = Math.min(minX, t.getStartX());
            minY = Math.min(minY, t.getStartY());
            maxX = Math.max(maxX, t.getEndX());
            maxY = Math.max(maxY, t.getEndY());
        }
        String feature = text.toString().trim();
        if (feature.isEmpty()) {
            return;
        }
        out.add(new OcrRow(
                source.getStreamName(),
                feature,
                confSum / run.size(),
                minX,
                minY,
                maxX,
                maxY,
                new OcrTokenMeta(OcrTokenMeta.Level.CHAR, new ArrayList<>(run))));
    }

    private static boolean tokenCenterInMaskZone(OcrTokenBox tok, List<FormulaPiece> formulas) {
        double cx = (tok.getStartX() + tok.getEndX()) / 2.0;
        double cy = (tok.getStartY() + tok.getEndY()) / 2.0;
        for (FormulaPiece f : formulas) {
            int leftOut = sideOutset(f.h(), MASK_LEFT_OUTSET_H_RATIO, 1, MASK_LEFT_OUTSET_MAX_PX);
            int rightOut = sideOutset(f.h(), MASK_RIGHT_OUTSET_H_RATIO, 1, MASK_RIGHT_OUTSET_MAX_PX);
            int vertOut = Math.max(1, Math.round(f.h() * MASK_VERT_OUTSET_H_RATIO));
            if (cx >= f.x() - leftOut && cx <= f.endX() + rightOut
                    && cy >= f.y() - vertOut && cy <= f.endY() + vertOut) {
                return true;
            }
        }
        return false;
    }

    /**
     * ⌀/公差残笔常被认成单字符 e/c/o/0：左贴公式前、右/下贴公式后。
     * 也处理「车e」合成一 token：剥掉贴公式缘的圆残笔字符。
     */
    private static boolean isFormulaEdgeResidue(OcrTokenBox tok, List<FormulaPiece> formulas) {
        if (tok == null || tok.getText() == null) {
            return false;
        }
        String t = tok.getText().trim();
        if (t.length() != 1 || !isCircleFragmentChar(t.charAt(0))) {
            return false;
        }
        double cx = (tok.getStartX() + tok.getEndX()) / 2.0;
        double cy = (tok.getStartY() + tok.getEndY()) / 2.0;
        for (FormulaPiece f : formulas) {
            double padL = Math.max(8.0, f.h() * 0.35);
            double padR = Math.max(8.0, f.h() * 0.40);
            double padB = Math.max(6.0, f.h() * 0.30);
            double into = Math.max(4.0, f.h() * 0.12);
            boolean yOk = cy >= f.y() - padB * 0.5 && cy <= f.endY() + padB;
            if (!yOk) {
                continue;
            }
            // 左：车|⌀ 之间的残笔
            if (cx >= f.x() - padL && cx <= f.x() + into) {
                return true;
            }
            // 右/下：公差下标
            if (cx >= f.x() && cx <= f.endX() + padR
                    && cy >= f.y() && cy <= f.endY() + padB) {
                return true;
            }
        }
        return false;
    }

    private static String stripResidueFromPlainFeature(
            String feature, OcrRow row, List<FormulaPiece> formulas) {
        if (feature == null) {
            return null;
        }
        String t = feature.trim();
        double cy = (row.getStartY() + row.getEndY()) / 2.0;
        while (t.length() > 0 && isCircleFragmentChar(t.charAt(t.length() - 1))) {
            if (!tokenEndNearFormulaLeft(row.getEndX(), cy, formulas)) {
                break;
            }
            t = t.substring(0, t.length() - 1).trim();
        }
        while (t.length() > 0 && isCircleFragmentChar(t.charAt(0))) {
            if (!tokenStartNearFormulaRight(row.getStartX(), cy, formulas)) {
                break;
            }
            t = t.substring(1).trim();
        }
        return t;
    }

    /** @return 剥残笔后的 token；整段都是残笔则 null */
    private static OcrTokenBox stripFormulaEdgeResidueChars(
            OcrTokenBox tok, List<FormulaPiece> formulas) {
        String t = tok.getText().trim();
        if (t.isEmpty()) {
            return null;
        }
        double cy = (tok.getStartY() + tok.getEndY()) / 2.0;
        StringBuilder sb = new StringBuilder(t);
        // 尾部贴公式左缘（…车e|$⌀）
        while (sb.length() > 0 && isCircleFragmentChar(sb.charAt(sb.length() - 1))) {
            if (!tokenEndNearFormulaLeft(tok.getEndX(), cy, formulas)) {
                break;
            }
            sb.setLength(sb.length() - 1);
        }
        // 头部贴公式右缘（$⌀|e至…）少见，一并剥
        while (sb.length() > 0 && isCircleFragmentChar(sb.charAt(0))) {
            if (!tokenStartNearFormulaRight(tok.getStartX(), cy, formulas)) {
                break;
            }
            sb.deleteCharAt(0);
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.equals(t)) {
            return tok;
        }
        return new OcrTokenBox(
                cleaned,
                tok.getConfidence(),
                tok.getStartX(),
                tok.getStartY(),
                tok.getEndX(),
                tok.getEndY());
    }

    private static boolean tokenEndNearFormulaLeft(double endX, double cy, List<FormulaPiece> formulas) {
        for (FormulaPiece f : formulas) {
            double padL = Math.max(10.0, f.h() * 0.40);
            double into = Math.max(4.0, f.h() * 0.15);
            boolean yOk = cy >= f.y() - 4 && cy <= f.endY() + 4;
            if (yOk && endX >= f.x() - padL && endX <= f.x() + into) {
                return true;
            }
        }
        return false;
    }

    private static boolean tokenStartNearFormulaRight(double startX, double cy, List<FormulaPiece> formulas) {
        for (FormulaPiece f : formulas) {
            double padR = Math.max(10.0, f.h() * 0.40);
            boolean yOk = cy >= f.y() - 4 && cy <= f.endY() + 8;
            if (yOk && startX >= f.endX() - 4 && startX <= f.endX() + padR) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCircleFragmentChar(char c) {
        return c == 'e' || c == 'E' || c == 'c' || c == 'C'
                || c == 'o' || c == 'O' || c == '0'
                // ⌀ 常被 OCR 认成 α/ø，贴在公式缘的单字符残笔应丢掉
                || c == 'α' || c == 'ø' || c == 'Ø' || c == '∅' || c == '⌀';
    }

    /** OCR 读到的逗号/句号一律保留，不因落在涂白外扩带丢掉。 */
    private static boolean isKeepPunctuation(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return "，".equals(t) || ",".equals(t) || "。".equals(t) || ".".equals(t);
    }

    private static boolean rowOverlapsFormula(
            OcrRow row, List<FormulaPiece> formulas, double threshold) {
        double rw = Math.max(1.0, row.getEndX() - row.getStartX());
        double rh = Math.max(1.0, row.getEndY() - row.getStartY());
        double rArea = rw * rh;
        for (FormulaPiece f : formulas) {
            double ox = Math.max(row.getStartX(), f.x());
            double oy = Math.max(row.getStartY(), f.y());
            double ex = Math.min(row.getEndX(), f.endX());
            double ey = Math.min(row.getEndY(), f.endY());
            if (ex <= ox || ey <= oy) {
                continue;
            }
            double overlap = (ex - ox) * (ey - oy);
            double fArea = Math.max(1.0, (double) f.w() * f.h());
            if (overlap / rArea >= threshold || overlap / fArea >= threshold) {
                return true;
            }
        }
        return false;
    }

    private static String stitch(List<OcrRow> texts, List<FormulaPiece> formulas) {
        List<Element> elements = new ArrayList<>();
        for (OcrRow row : texts) {
            String feat = row.getFeature().trim();
            elements.add(new Element(
                    (row.getStartY() + row.getEndY()) / 2.0,
                    row.getStartX(),
                    row.getEndX(),
                    false,
                    false,
                    feat));
        }
        for (FormulaPiece f : formulas) {
            elements.add(new Element(
                    f.centerY(),
                    f.x(),
                    f.endX(),
                    true,
                    f.display(),
                    formatFormula(f)));
        }
        if (elements.isEmpty()) {
            return "";
        }

        elements.sort(Comparator
                .comparingDouble((Element e) -> e.yCenter)
                .thenComparingDouble(e -> e.x));

        double yTol = 40.0;
        for (FormulaPiece f : formulas) {
            yTol = Math.max(yTol, f.h() * 0.8);
        }

        List<List<Element>> rows = new ArrayList<>();
        List<Element> current = new ArrayList<>();
        double currentY = Double.NaN;
        for (Element e : elements) {
            if (current.isEmpty() || Double.isNaN(currentY) || Math.abs(e.yCenter - currentY) < yTol) {
                current.add(e);
                currentY = Double.isNaN(currentY) ? e.yCenter : (currentY + e.yCenter) / 2.0;
            } else {
                current.sort(Comparator.comparingDouble(el -> el.x));
                rows.add(current);
                current = new ArrayList<>();
                current.add(e);
                currentY = e.yCenter;
            }
        }
        if (!current.isEmpty()) {
            current.sort(Comparator.comparingDouble(el -> el.x));
            rows.add(current);
        }

        StringBuilder out = new StringBuilder();
        for (List<Element> row : rows) {
            StringBuilder line = new StringBuilder();
            for (Element e : row) {
                if (e.formula && e.display && line.length() > 0) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(line);
                    line.setLength(0);
                    out.append('\n').append(e.content);
                    continue;
                }
                line.append(e.content);
            }
            if (line.length() > 0) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        return out.toString();
    }

    private static String formatFormula(FormulaPiece f) {
        String latex = f.latex().trim();
        if (latex.isEmpty()) {
            return "";
        }
        if (f.display()) {
            if (latex.startsWith("$$")) {
                return latex;
            }
            return "$$" + latex + "$$";
        }
        if (latex.startsWith("$")) {
            return latex;
        }
        return "$" + latex + "$";
    }

    private static final class Element {
        final double yCenter;
        final double x;
        final double endX;
        final boolean formula;
        final boolean display;
        final String content;

        Element(double yCenter, double x, double endX, boolean formula, boolean display, String content) {
            this.yCenter = yCenter;
            this.x = x;
            this.endX = endX;
            this.formula = formula;
            this.display = display;
            this.content = content;
        }
    }
}
