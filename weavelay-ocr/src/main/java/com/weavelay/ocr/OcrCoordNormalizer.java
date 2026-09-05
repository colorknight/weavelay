package com.weavelay.ocr;

import com.weavelay.core.model.OcrRow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/**
 * 将 RapidOCR 检测框坐标对齐到原图 PNG 像素空间.
 */
final class OcrCoordNormalizer {

    private OcrCoordNormalizer() {
    }

    static void alignToSourceImage(List<OcrRow> rows, Path imagePath, OcrParamSettings params) {
        if (rows == null || rows.isEmpty() || imagePath == null) {
            return;
        }
        int[] size = readImageSize(imagePath);
        if (size == null) {
            return;
        }
        alignToSourceImage(rows, size[0], size[1], params);
    }

    static void alignToSourceImage(List<OcrRow> rows, int imageWidth, int imageHeight,
                                    OcrParamSettings params) {
        if (rows == null || rows.isEmpty() || imageWidth < 1 || imageHeight < 1) {
            return;
        }
        double maxX = 0;
        double maxY = 0;
        for (OcrRow row : rows) {
            if (row == null || !row.hasBbox()) continue;
            maxX = Math.max(maxX, row.getEndX());
            maxY = Math.max(maxY, row.getEndY());
        }
        if (maxX <= 0 || maxY <= 0) return;

        // 情况1: 检测框坐标超出原图 → 缩小
        if (maxX > imageWidth * 1.05 || maxY > imageHeight * 1.05) {
            double scale = Math.min(imageWidth / maxX, imageHeight / maxY);
            if (scale > 0 && scale < 0.98) {
                scaleRows(rows, scale, scale);
            }
            return;
        }

        // 情况2: maxSideLen 缩放后未放大回原图空间
        int maxSideLen = params.getMaxSideLen();
        if (maxSideLen > 0) {
            int originalMaxSide = Math.max(imageWidth, imageHeight);
            if (originalMaxSide > maxSideLen) {
                double resizeRatio = (double) maxSideLen / originalMaxSide;
                double resizedW = imageWidth * resizeRatio;
                double resizedH = imageHeight * resizeRatio;

                boolean exceedsResizedX = maxX > resizedW * 1.05;
                boolean exceedsResizedY = maxY > resizedH * 1.05;
                boolean nearOriginal = maxX > imageWidth * 0.85
                                    || maxY > imageHeight * 0.85;

                if (!exceedsResizedX && !exceedsResizedY && !nearOriginal) {
                    double scale = (double) originalMaxSide / maxSideLen;
                    scaleRows(rows, scale, scale);
                }
            }
        }
    }

    private static void scaleRows(List<OcrRow> rows, double scaleX, double scaleY) {
        for (OcrRow row : rows) {
            if (row == null || !row.hasBbox()) continue;
            row.setStartX(row.getStartX() * scaleX);
            row.setStartY(row.getStartY() * scaleY);
            row.setEndX(row.getEndX() * scaleX);
            row.setEndY(row.getEndY() * scaleY);
            OcrTokenMeta meta = OcrTokenMeta.fromMetas(row.getMetas());
            if (meta != null) {
                for (OcrTokenBox token : meta.getTokens()) {
                    token.scale(scaleX, scaleY);
                }
            }
        }
    }

    private static int[] readImageSize(Path imagePath) {
        try {
            BufferedImage img = ImageIO.read(imagePath.toFile());
            if (img == null) return null;
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception ex) {
            return null;
        }
    }
}
