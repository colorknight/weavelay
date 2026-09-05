package com.weavelay.core.layout;

import com.weavelay.core.model.OcrRow;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;

public final class TableBorderDetector {

    private static final int MERGE_GAP = 10;
    private static final double MIN_CELL_SIZE = 8.0;
    private static final double DEFAULT_ROW_THRESHOLD = 12.0;

    private TableBorderDetector() {}

    public static List<CellRect> detectCells(List<OcrRow> ocrRows, String label) {
        return detectCells(ocrRows, label, DEFAULT_ROW_THRESHOLD);
    }

    public static List<CellRect> detectCells(List<OcrRow> ocrRows, String label, double thresholdY) {
        if (ocrRows == null || ocrRows.isEmpty() || label == null) return Collections.emptyList();
        int colon = label.indexOf(':');
        if (colon < 0) return Collections.emptyList();
        int eq = label.indexOf('=', colon + 1);
        String colsStr = eq > colon ? label.substring(colon + 1, eq) : label.substring(colon + 1);
        String[] headerNames = colsStr.split(",");
        int numCols = headerNames.length;
        if (numCols == 0) return Collections.emptyList();

        List<OcrRow> sorted = new ArrayList<>(ocrRows);
        sorted.removeIf(r -> !r.hasBbox());
        if (sorted.isEmpty()) return Collections.emptyList();
        sorted.sort(Comparator.comparingDouble(OcrRow::getStartY).thenComparingDouble(OcrRow::getStartX));

        double overallMinX = sorted.stream().mapToDouble(OcrRow::getStartX).min().orElse(0);
        double overallMaxX = sorted.stream().mapToDouble(OcrRow::getEndX).max().orElse(100);
        double colWidth = (overallMaxX - overallMinX) / numCols;
        List<Double> colBounds = new ArrayList<>();
        for (int i = 0; i <= numCols; i++) colBounds.add(overallMinX + colWidth * i);
        double[] colCenters = new double[numCols];
        for (int c = 0; c < numCols; c++) colCenters[c] = (colBounds.get(c) + colBounds.get(c + 1)) / 2.0;

        List<List<OcrRow>> tableRows = groupByY(sorted, thresholdY);
        List<Double> rowHeights = new ArrayList<>();
        for (OcrRow r : sorted) { double h = r.getEndY() - r.getStartY(); if (h > MIN_CELL_SIZE) rowHeights.add(h); }
        rowHeights.sort(Double::compare);
        double medianHeight = rowHeights.isEmpty() ? thresholdY : rowHeights.get(rowHeights.size() / 2);

        List<CellRect> cells = new ArrayList<>();
        for (int r = 0; r < tableRows.size(); r++) {
            List<OcrRow> rowGroup = tableRows.get(r);
            double rowMinY = rowGroup.stream().mapToDouble(OcrRow::getStartY).min().orElse(0);
            double rowMaxY = rowGroup.stream().mapToDouble(OcrRow::getEndY).max().orElse(0);
            double rowH = rowMaxY - rowMinY;
            String[] colTexts = new String[numCols];
            double[] colHeights = new double[numCols];
            for (OcrRow ocr : rowGroup) {
                int bestCol = nearestColumn(ocr.centerX(), colCenters);
                String text = ocr.getFeature() != null ? ocr.getFeature().trim() : "";
                if (text.isEmpty()) {
                    continue;
                }
                if (colTexts[bestCol] == null || colTexts[bestCol].isEmpty()) {
                    colTexts[bestCol] = text;
                    colHeights[bestCol] = ocr.getEndY() - ocr.getStartY();
                } else {
                    colTexts[bestCol] = colTexts[bestCol] + " " + text;
                    colHeights[bestCol] = Math.max(colHeights[bestCol], ocr.getEndY() - ocr.getStartY());
                }
            }
            for (int c = 0; c < numCols; c++) {
                double cellX = colBounds.get(c);
                double cellW = colBounds.get(c + 1) - cellX;
                String text = colTexts[c] != null ? colTexts[c] : "";
                int rowspan = (!text.isEmpty() && colHeights[c] > medianHeight * 1.6) ? 2 : 1;
                cells.add(new CellRect(cellX, rowMinY, cellW, rowH, r, c, rowspan, 1, text));
            }
        }
        return cells;
    }

    public static List<CellRect> detectCells(byte[] imageBytes, List<OcrRow> ocrRows, String label) {
        if (imageBytes == null || ocrRows == null || label == null) return Collections.emptyList();
        int colon = label.indexOf(':');
        if (colon < 0) return Collections.emptyList();
        int eq = label.indexOf('=', colon + 1);
        String colsStr = eq > colon ? label.substring(colon + 1, eq) : label.substring(colon + 1);
        String[] headerNames = colsStr.split(",");
        if (headerNames.length == 0) return Collections.emptyList();

        Mat gray = null, binaryInv = null, vClean = null, hClean = null;
        try {
            gray = pngBytesToGrayMat(imageBytes);
            int cols = gray.cols(), rows = gray.rows();
            binaryInv = new Mat();
            threshold(gray, binaryInv, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);

            int vKH = Math.max(rows / 10, 70);
            Mat vDilateK = org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement(
                    org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT, new Size(1, 7));
            Mat vPre = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.dilate(binaryInv, vPre, vDilateK);
            vDilateK.close();
            Mat vOpenK = org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement(
                    org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT, new Size(1, vKH));
            vClean = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.morphologyEx(vPre, vClean,
                    org.bytedeco.opencv.global.opencv_imgproc.MORPH_OPEN, vOpenK);
            vOpenK.close(); vPre.close();

            int hKW = Math.max(cols / 10, 70);
            Mat hDilateK = org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement(
                    org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT, new Size(7, 1));
            Mat hPre = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.dilate(binaryInv, hPre, hDilateK);
            hDilateK.close();
            Mat hOpenK = org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement(
                    org.bytedeco.opencv.global.opencv_imgproc.MORPH_RECT, new Size(hKW, 1));
            hClean = new Mat();
            org.bytedeco.opencv.global.opencv_imgproc.morphologyEx(hPre, hClean,
                    org.bytedeco.opencv.global.opencv_imgproc.MORPH_OPEN, hOpenK);
            hOpenK.close(); hPre.close();

            List<Integer> hLines = detectHorizontalLines(hClean, cols, rows);
            List<Integer> vLines = detectVerticalLines(vClean, cols, rows);

            if (!vLines.contains(0)) vLines.add(0, 0);
            if (!vLines.contains(cols - 1)) vLines.add(cols - 1);
            if (!hLines.contains(0)) hLines.add(0, 0);
            if (!hLines.contains(rows - 1)) hLines.add(rows - 1);
            Collections.sort(hLines);
            Collections.sort(vLines);

            if (hLines.size() < 2 || vLines.size() < 2) {
                return detectCells(ocrRows, label, DEFAULT_ROW_THRESHOLD);
            }

            return buildCellGrid(hLines, vLines, ocrRows);
        } catch (Exception e) {
            return detectCells(ocrRows, label, DEFAULT_ROW_THRESHOLD);
        } finally {
            if (gray != null) gray.close();
            if (binaryInv != null) binaryInv.close();
            if (vClean != null) vClean.close();
            if (hClean != null) hClean.close();
        }
    }

    private static Mat pngBytesToGrayMat(byte[] pngBytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (img == null) throw new IOException("无法解析 PNG");
        int w = img.getWidth(), h = img.getHeight();
        Mat gray = new Mat(h, w, CV_8UC1);
        UByteIndexer idx = gray.createIndexer();
        try {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                idx.put(y, x, (int)(0.299*((rgb>>16)&0xFF) + 0.587*((rgb>>8)&0xFF) + 0.114*(rgb&0xFF)));
            }
        } finally { idx.release(); }
        return gray;
    }

    private static List<Integer> detectHorizontalLines(Mat binaryInv, int cols, int rows) {
        UByteIndexer idx = binaryInv.createIndexer();
        int threshold = Math.max((int)(cols * 0.05), 2);
        List<Integer> raw = new ArrayList<>();
        try {
            for (int y = 0; y < rows; y++) {
                int count = 0;
                for (int x = 0; x < cols; x++) if ((idx.get(y, x) & 0xFF) > 0) count++;
                if (count >= threshold) raw.add(y);
            }
        } finally { idx.release(); }
        return mergeLinePositions(raw, MERGE_GAP);
    }

    private static List<Integer> detectVerticalLines(Mat binaryInv, int cols, int rows) {
        UByteIndexer idx = binaryInv.createIndexer();
        int threshold = Math.max((int)(rows * 0.01), 2);
        List<Integer> raw = new ArrayList<>();
        try {
            for (int x = 0; x < cols; x++) {
                int count = 0;
                for (int y = 0; y < rows; y++) if ((idx.get(y, x) & 0xFF) > 0) count++;
                if (count >= threshold) raw.add(x);
            }
        } finally { idx.release(); }
        return mergeLinePositions(raw, MERGE_GAP);
    }

    private static List<Integer> mergeLinePositions(List<Integer> raw, int maxGap) {
        if (raw.isEmpty()) return Collections.emptyList();
        List<Integer> sorted = new ArrayList<>(raw);
        Collections.sort(sorted);
        List<Integer> merged = new ArrayList<>();
        int start = sorted.get(0), end = start;
        for (int i = 1; i < sorted.size(); i++) {
            int p = sorted.get(i);
            if (p <= end + maxGap) { end = Math.max(end, p); }
            else { merged.add((start + end) / 2); start = p; end = p; }
        }
        merged.add((start + end) / 2);
        return merged;
    }

    static List<CellRect> buildCellGrid(List<Integer> hLines, List<Integer> vLines, List<OcrRow> ocrRows) {
        Collections.sort(hLines);
        Collections.sort(vLines);
        int rowCount = hLines.size() - 1;
        int colCount = vLines.size() - 1;

        int[] bestRow = new int[ocrRows.size()];
        int[] bestCol = new int[ocrRows.size()];
        for (int i = 0; i < ocrRows.size(); i++) {
            OcrRow ocr = ocrRows.get(i);
            double bestOverlap = 0;
            bestRow[i] = -1;
            bestCol[i] = -1;
            for (int r = 0; r < rowCount; r++) {
                int rowTop = hLines.get(r), rowBottom = hLines.get(r + 1);
                for (int c = 0; c < colCount; c++) {
                    int colLeft = vLines.get(c), colRight = vLines.get(c + 1);
                    double ox = Math.max(ocr.getStartX(), colLeft);
                    double oy = Math.max(ocr.getStartY(), rowTop);
                    double ex = Math.min(ocr.getEndX(), colRight);
                    double ey = Math.min(ocr.getEndY(), rowBottom);
                    if (ex > ox && ey > oy) {
                        double overlap = (ex - ox) * (ey - oy);
                        if (overlap > bestOverlap) {
                            bestOverlap = overlap;
                            bestRow[i] = r;
                            bestCol[i] = c;
                        }
                    }
                }
            }
        }

        List<CellRect> cells = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            int rowTop = hLines.get(r), rowBottom = hLines.get(r + 1);
            double cellH = rowBottom - rowTop;
            if (cellH < MIN_CELL_SIZE) continue;
            for (int c = 0; c < colCount; c++) {
                int colLeft = vLines.get(c), colRight = vLines.get(c + 1);
                double cellW = colRight - colLeft;
                if (cellW < MIN_CELL_SIZE) continue;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < ocrRows.size(); i++) {
                    if (bestRow[i] == r && bestCol[i] == c) {
                        String t = ocrRows.get(i).getFeature() != null ? ocrRows.get(i).getFeature().trim() : "";
                        if (!t.isEmpty()) { if (sb.length() > 0) sb.append(' '); sb.append(t); }
                    }
                }
                cells.add(new CellRect(colLeft, rowTop, cellW, cellH, r, c, 1, 1, sb.toString()));
            }
        }
        return cells;
    }

    private static List<List<OcrRow>> groupByY(List<OcrRow> sorted, double thresholdY) {
        List<List<OcrRow>> groups = new ArrayList<>();
        List<OcrRow> current = new ArrayList<>();
        double groupStartY = sorted.get(0).getStartY();
        for (OcrRow r : sorted) {
            if (r.getStartY() <= groupStartY + thresholdY) current.add(r);
            else { groups.add(current); current = new ArrayList<>(); current.add(r); groupStartY = r.getStartY(); }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static int nearestColumn(double cx, double[] colCenters) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int c = 0; c < colCenters.length; c++) {
            double dist = Math.abs(cx - colCenters[c]);
            if (dist < bestDist) { bestDist = dist; best = c; }
        }
        return best;
    }
}
