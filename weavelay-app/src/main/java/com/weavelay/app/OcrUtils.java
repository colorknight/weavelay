package com.weavelay.app;

import com.weavelay.app.ui.RectRegion;
import com.weavelay.core.merge.RowMerger;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.WeavePageResult;
import com.weavelay.core.pair.OcrBoxDeduper;
import com.weavelay.ocr.OcrRuntimeConfig;
import com.weavelay.ocr.RapidOcrService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;

/**
 * OCR 行处理、裁图、文字提取等静态工具方法。
 */
public final class OcrUtils {

    private OcrUtils() {}

    private static final double LINE_READING_MIN_VERTICAL_OVERLAP_PX = 12.0;

    /**
     * 从框选区域提取 OCR 文字行。
     */
    public static List<OcrRow> extractRegionOcrRows(WeavePageResult page, Path selectedPath,
                                                     int currentPageIndex, RectRegion region,
                                                     RapidOcrService ocrService) {
        try {
            // 先试 PDF 文字层提取
            if (selectedPath != null) {
                try {
                    String text = com.weavelay.ocr.pdf.PdfTextExtractor.extractText(
                            selectedPath, currentPageIndex,
                            region.getX(), region.getY(),
                            region.getWidth(), region.getHeight());
                    if (text != null && !text.isEmpty()) {
                        String[] lines = text.split("\\n");
                        List<OcrRow> rows = new ArrayList<>();
                        for (String line : lines) {
                            line = line.trim();
                            if (!line.isEmpty()) {
                                rows.add(new OcrRow(null, line, 1.0, 0, 0, 0, 0, null));
                            }
                        }
                        if (!rows.isEmpty()) return rows;
                    }
                } catch (Exception ignored) {
                }
            }
            // PDF 文字层为空, 用 OCR (ONNX)
            if (ocrService != null) {
                RapidOcrService.prepareNativeRuntime();
                ocrService.applyRuntime(new OcrRuntimeConfig());
                BufferedImage fullImg = ImageIO.read(new ByteArrayInputStream(page.getPagePng()));
                if (fullImg != null) {
                    int x = Math.max(0, (int) region.getX());
                    int y = Math.max(0, (int) region.getY());
                    int w = Math.min((int) region.getWidth(), fullImg.getWidth() - x);
                    int h = Math.min((int) region.getHeight(), fullImg.getHeight() - y);
                    if (w > 0 && h > 0) {
                        BufferedImage crop = fullImg.getSubimage(x, y, w, h);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        ImageIO.write(crop, "PNG", bos);
                        return ocrService.recognizeImageBytes(bos.toByteArray(), page.getStreamName());
                    }
                }
            }
        } catch (Exception ex) {
            // ignore
        }
        return List.of();
    }

    /**
     * 把引擎刚吐出的 OCR 行写成 UTF-8 文本，便于对照「识别丢了还是配对丢了」。
     * 最近一次：{@code <tmpdir>/weavelay-ocr-last.txt}；全程追加：{@code weavelay-ocr-session.log}。
     */
    public static Path lastDumpPath() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).resolve("weavelay-ocr-last.txt");
    }

    public static Path sessionDumpPath() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).resolve("weavelay-ocr-session.log");
    }

    public static Path dumpRawOcrRows(String tag, int imageW, int imageH, List<OcrRow> rows) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir", "."));
        Path last = lastDumpPath();
        Path session = sessionDumpPath();
        String body = formatRawOcrDump(tag, imageW, imageH, rows);
        try {
            Files.createDirectories(dir);
            Files.writeString(last, body, StandardCharsets.UTF_8);
            Files.writeString(session, body + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            System.err.println("[OCR dump] write failed: " + ex.getMessage());
        }
        System.out.println(body);
        System.out.println("[OCR dump] " + last.toAbsolutePath());
        return last;
    }

    static String formatRawOcrDump(String tag, int imageW, int imageH, List<OcrRow> rows) {
        StringBuilder sb = new StringBuilder();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        int n = rows == null ? 0 : rows.size();
        sb.append("===== OCR RAW  ").append(ts).append("  tag=").append(tag == null ? "" : tag)
                .append("  image=").append(imageW).append('x').append(imageH)
                .append("  lines=").append(n).append(" =====\n");
        if (rows == null || rows.isEmpty()) {
            sb.append("(empty)\n");
            return sb.toString();
        }
        int i = 1;
        for (OcrRow row : rows) {
            if (row == null) {
                continue;
            }
            String text = row.getFeature() == null ? "" : row.getFeature();
            sb.append(String.format(
                    "%03d  conf=%.3f  box=[%.0f,%.0f,%.0f,%.0f]  %s%n",
                    i++,
                    row.getProb(),
                    row.getStartX(), row.getStartY(), row.getEndX(), row.getEndY(),
                    text));
        }
        return sb.toString();
    }

    /**
     * 裁切页面PNG区域，返回裁切后的PNG字节。
     */
    public static byte[] cropPagePng(byte[] pagePng, int x, int y, int w, int h) throws Exception {
        if (w <= 0 || h <= 0 || pagePng == null || pagePng.length == 0) return null;
        BufferedImage full = ImageIO.read(new ByteArrayInputStream(pagePng));
        return cropBufferedImage(full, x, y, w, h);
    }

    /**
     * 从已解码整页图裁切（同一页多次裁剪时避免反复解析 PNG）。
     */
    public static byte[] cropBufferedImage(BufferedImage full, int x, int y, int w, int h) throws Exception {
        BufferedImage crop = cropToBufferedImage(full, x, y, w, h);
        return toPngBytes(crop);
    }

    /**
     * 裁切为独立 {@link BufferedImage}（拷贝像素，不共享原图缓冲区），供 OCR 直传，免 PNG 编解码。
     */
    public static BufferedImage cropToBufferedImage(BufferedImage full, int x, int y, int w, int h) {
        if (full == null || w <= 0 || h <= 0) {
            return null;
        }
        x = Math.max(0, Math.min(x, full.getWidth() - 1));
        y = Math.max(0, Math.min(y, full.getHeight() - 1));
        w = Math.min(w, full.getWidth() - x);
        h = Math.min(h, full.getHeight() - y);
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage sub = full.getSubimage(x, y, w, h);
        int type = sub.getType() != BufferedImage.TYPE_CUSTOM
                ? sub.getType()
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage copy = new BufferedImage(w, h, type);
        java.awt.Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(sub, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    /** 需要 OpenCV/公式 API 等吃字节时再编码。 */
    public static byte[] toPngBytes(BufferedImage image) throws Exception {
        if (image == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", bos);
        return bos.toByteArray();
    }

    /**
     * 把 OCR 行列表转为纯文本（换行分隔）。
     */
    public static String ocrRowsToText(List<OcrRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (OcrRow r : rows) {
            if (r.getFeature() != null && !r.getFeature().trim().isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(r.getFeature().trim());
            }
        }
        return sb.toString();
    }

    /**
     * 从整页 OCR 结果中筛落在区域内的行（中心点落在框内，或与框有明显重叠）。
     */
    public static List<OcrRow> rowsInRegion(List<OcrRow> pageRows, int x, int y, int w, int h) {
        if (pageRows == null || pageRows.isEmpty() || w <= 0 || h <= 0) {
            return List.of();
        }
        double x0 = x;
        double y0 = y;
        double x1 = x + (double) w;
        double y1 = y + (double) h;
        double area = Math.max(1.0, w * (double) h);
        List<OcrRow> out = new ArrayList<>();
        for (OcrRow r : pageRows) {
            if (r == null || !r.hasBbox()) {
                continue;
            }
            double cx = r.centerX();
            double cy = r.centerY();
            if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1) {
                out.add(r);
                continue;
            }
            double ox = Math.max(r.getStartX(), x0);
            double oy = Math.max(r.getStartY(), y0);
            double ex = Math.min(r.getEndX(), x1);
            double ey = Math.min(r.getEndY(), y1);
            if (ex > ox && ey > oy) {
                double overlap = (ex - ox) * (ey - oy);
                double rowArea = Math.max(1.0,
                        (r.getEndX() - r.getStartX()) * (r.getEndY() - r.getStartY()));
                if (overlap / rowArea >= 0.45 || overlap / area >= 0.02) {
                    out.add(r);
                }
            }
        }
        return sortedOcrRows(out);
    }

    /**
     * 把整页坐标下的区域行平移为裁剪图局部坐标（供表格配对使用）。
     */
    public static List<OcrRow> translateRowsToCrop(List<OcrRow> pageRegionRows, int cropX, int cropY) {
        if (pageRegionRows == null || pageRegionRows.isEmpty()) {
            return List.of();
        }
        List<OcrRow> out = new ArrayList<>(pageRegionRows.size());
        for (OcrRow r : pageRegionRows) {
            if (r == null) {
                continue;
            }
            out.add(new OcrRow(
                    r.getStreamName(),
                    r.getFeature(),
                    r.getProb(),
                    r.getStartX() - cropX,
                    r.getStartY() - cropY,
                    r.getEndX() - cropX,
                    r.getEndY() - cropY,
                    r.getMetas()));
        }
        return out;
    }

    /**
     * 排序+去重 OCR 行。
     */
    public static List<OcrRow> sortedOcrRows(List<OcrRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<OcrRow> deduped = OcrBoxDeduper.dropStrictlyContained(rows);
        return RowMerger.sortForReading(deduped, LINE_READING_MIN_VERTICAL_OVERLAP_PX);
    }

    /**
     * 从一组 OCR 行中按 X 坐标重叠自动检测列。
     */
    public static List<List<OcrRow>> detectColumns(List<OcrRow> rows) {
        if (rows == null || rows.size() < 2) {
            return java.util.Collections.singletonList(rows == null ? java.util.Collections.emptyList() : rows);
        }
        List<double[]> intervals = new ArrayList<>();
        for (OcrRow row : rows) {
            if (!row.hasBbox()) continue;
            intervals.add(new double[]{row.getStartX(), row.getEndX()});
        }
        if (intervals.isEmpty()) return java.util.Collections.singletonList(rows);

        List<List<Integer>> clusters = new ArrayList<>();
        boolean[] used = new boolean[intervals.size()];
        for (int i = 0; i < intervals.size(); i++) {
            if (used[i]) continue;
            List<Integer> cluster = new ArrayList<>();
            cluster.add(i);
            used[i] = true;
            for (int j = i + 1; j < intervals.size(); j++) {
                if (used[j]) continue;
                double overlap = Math.min(intervals.get(i)[1], intervals.get(j)[1])
                        - Math.max(intervals.get(i)[0], intervals.get(j)[0]);
                double minW = Math.min(intervals.get(i)[1] - intervals.get(i)[0],
                        intervals.get(j)[1] - intervals.get(j)[0]);
                if (overlap > 0 && overlap / Math.max(minW, 1) > 0.3) {
                    cluster.add(j);
                    used[j] = true;
                }
            }
            clusters.add(cluster);
        }
        clusters.sort(Comparator.comparingDouble(
                c -> (intervals.get(c.get(0))[0] + intervals.get(c.get(0))[1]) / 2));
        List<List<OcrRow>> cols = new ArrayList<>();
        for (List<Integer> cl : clusters) {
            List<OcrRow> colRows = new ArrayList<>();
            for (int idx : cl) colRows.add(rows.get(idx));
            colRows.sort(Comparator.comparingDouble(OcrRow::getStartY));
            cols.add(colRows);
        }
        return cols;
    }

    /**
     * Levenshtein 编辑距离，用于容忍 OCR 错字匹配。
     */
    public static int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[m];
    }

    /**
     * 过滤垂直列头碎片：检测同X、跨行、单字拼起来=列头的 OCR 碎片并移除。
     */
    public static List<OcrRow> removeVerticalLabelFragments(List<OcrRow> ocrRows, String[] headerCols) {
        if (ocrRows == null || ocrRows.isEmpty() || headerCols == null || headerCols.length == 0) {
            return ocrRows;
        }
        // 收集中文字符级别的 OCR 碎片
        List<OcrRow> candidates = new ArrayList<>();
        for (OcrRow r : ocrRows) {
            if (!r.hasBbox()) continue;
            String t = r.getFeature().trim();
            if (t.isEmpty()) continue;
            boolean inHeader = false;
            for (String h : headerCols) {
                if (h.contains(t)) { inHeader = true; break; }
            }
            if (inHeader) candidates.add(r);
        }

        // 按 X 坐标分组（容差 15px）
        List<List<OcrRow>> xGroups = new ArrayList<>();
        for (OcrRow r : candidates) {
            boolean added = false;
            for (List<OcrRow> g : xGroups) {
                if (Math.abs(g.get(0).centerX() - r.centerX()) < 15) {
                    g.add(r); added = true; break;
                }
            }
            if (!added) {
                List<OcrRow> ng = new ArrayList<>();
                ng.add(r);
                xGroups.add(ng);
            }
        }

        // 对每组，按 Y 排序后拼接文字，若等于任一列头则全部移除
        Set<OcrRow> toRemove = new HashSet<>();
        for (List<OcrRow> g : xGroups) {
            if (g.size() < 2) continue;
            g.sort(Comparator.comparingDouble(OcrRow::getStartY));
            StringBuilder sb = new StringBuilder();
            for (OcrRow r : g) sb.append(r.getFeature().trim());
            String combined = sb.toString();
            for (String h : headerCols) {
                String hClean = h.trim();
                if (combined.equals(hClean) || hClean.equals(combined)) {
                    toRemove.addAll(g);
                    break;
                }
            }
        }

        if (toRemove.isEmpty()) return ocrRows;
        List<OcrRow> filtered = new ArrayList<>();
        for (OcrRow r : ocrRows) {
            if (!toRemove.contains(r)) filtered.add(r);
        }
        return filtered;
    }
}
