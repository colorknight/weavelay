package com.weavelay.app.ui;

import com.weavelay.app.ui.PageImageFactory;
import com.weavelay.core.layout.CellRect;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

final class OcrBoxOverlay {
    private static final Color BOX_STROKE = Color.web((String)"#E53935");
    private static final Color HIGHLIGHT_STROKE = Color.web((String)"#FF9800");
    private static final Color HIGHLIGHT_FILL = Color.web((String)"#FF9800", (double)0.18);
    private static final Color LABEL_STROKE = Color.web((String)"#1565C0");
    private static final Color VALUE_STROKE = Color.web((String)"#2E7D32");
    private static final Color LINK_STROKE = Color.web((String)"#FB8C00");
    private static final double OCR_WIDTH = 2.5;
    private static final double HIGHLIGHT_WIDTH = 3.0;
    private static final java.awt.Color AWT_OCR = java.awt.Color.decode("#E53935");
    private static final Color VIRTUAL_CELL_STROKE = Color.web((String)"#1565C0");
    private static final Color VIRTUAL_CELL_FILL = Color.web((String)"#1565C0", (double)0.08);
    private static final Color VIRTUAL_HIGHLIGHT_STROKE = Color.web((String)"#FF9800");
    private static final Color VIRTUAL_HIGHLIGHT_FILL = Color.web((String)"#FF9800", (double)0.22);

    private OcrBoxOverlay() {
    }

    static ImageView buildPlainImageView(Image image) {
        ImageView view = new ImageView(image);
        view.setSmooth(true);
        return view;
    }

    static ImageView buildAnnotatedImageView(byte[] pagePng, List<OcrRow> ocrRows) {
        return OcrBoxOverlay.buildAnnotatedImageView(pagePng, ocrRows, true);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    static ImageView buildAnnotatedImageView(byte[] pagePng, List<OcrRow> ocrRows, boolean drawOcrBoxes) {
        BufferedImage buffered = PageImageFactory.loadBuffered(pagePng);
        if (drawOcrBoxes && ocrRows != null && !ocrRows.isEmpty()) {
            Graphics2D g = buffered.createGraphics();
            try {
                g.setStroke(new BasicStroke(2.5f));
                g.setColor(AWT_OCR);
                for (OcrRow row : ocrRows) {
                    OcrBoxOverlay.strokeRow(g, row);
                }
            }
            finally {
                g.dispose();
            }
        }
        WritableImage image = PageImageFactory.toWritableImage(buffered);
        return OcrBoxOverlay.buildPlainImageView((Image)image);
    }

    static Pane buildInteractivePreview(byte[] pagePng, List<OcrRow> ocrRows, double width, double height) {
        WritableImage image = PageImageFactory.toWritableImage(PageImageFactory.loadBuffered(pagePng));
        ImageView view = OcrBoxOverlay.buildPlainImageView((Image)image);
        Canvas overlay = OcrBoxOverlay.createCanvas((Image)image);
        overlay.setMouseTransparent(false);
        OcrBoxOverlay.redrawOverlay(overlay, ocrRows, null);
        Pane pane = new Pane();
        view.setLayoutX(0.0);
        view.setLayoutY(0.0);
        overlay.setLayoutX(0.0);
        overlay.setLayoutY(0.0);
        pane.getChildren().addAll(view, overlay);
        double pw = Math.max(width, image.getWidth());
        double ph = Math.max(height, image.getHeight());
        pane.setMinSize(pw, ph);
        pane.setPrefSize(pw, ph);
        pane.setMaxSize(pw, ph);
        return pane;
    }

    static void redrawOverlay(Canvas canvas, List<OcrRow> ocrRows, List<OcrRow> virtualCellRows, List<OcrRow> highlightRows) {
        if (canvas == null) {
            return;
        }
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0.0, 0.0, w, h);
        if (ocrRows != null) {
            for (OcrRow row : ocrRows) {
                if (row == null || !row.hasBbox() || OcrBoxOverlay.isHighlighted(row, highlightRows)) continue;
                OcrBoxOverlay.strokeRowFx(gc, row, BOX_STROKE, 2.5, null);
            }
        }
        if (virtualCellRows != null) {
            for (OcrRow row : virtualCellRows) {
                if (row == null || !row.hasBbox() || OcrBoxOverlay.isHighlighted(row, highlightRows)) continue;
                OcrBoxOverlay.strokeVirtualCellFx(gc, row, false);
            }
        }
        if (ocrRows != null) {
            for (OcrRow row : ocrRows) {
                if (row == null || !row.hasBbox() || !OcrBoxOverlay.isHighlighted(row, highlightRows)) continue;
                OcrBoxOverlay.strokeRowFx(gc, row, HIGHLIGHT_STROKE, 3.0, HIGHLIGHT_FILL);
            }
        }
        if (highlightRows != null) {
            for (OcrRow row : highlightRows) {
                if (row == null || !row.hasBbox() || ocrRows != null && OcrBoxOverlay.isHighlighted(row, ocrRows)) continue;
                OcrBoxOverlay.strokeRowFx(gc, row, HIGHLIGHT_STROKE, 3.0, HIGHLIGHT_FILL);
            }
        }
        if (virtualCellRows != null) {
            for (OcrRow row : virtualCellRows) {
                if (row == null || !row.hasBbox() || !OcrBoxOverlay.isHighlighted(row, highlightRows)) continue;
                OcrBoxOverlay.strokeVirtualCellFx(gc, row, true);
            }
        }
    }

    static void redrawOverlay(Canvas canvas, List<OcrRow> ocrRows, List<OcrRow> highlightRows) {
        OcrBoxOverlay.redrawOverlay(canvas, ocrRows, null, highlightRows);
    }

    static void drawArrowTo(Canvas canvas, double fromX, double fromY, double toX, double toY) {
        if (canvas == null) {
            return;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 10.0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setStroke((Paint)Color.web((String)"#FF9800", (double)0.33));
        gc.setLineWidth(6.0);
        gc.strokeLine(fromX, fromY, toX, toY);
        gc.setStroke((Paint)Color.web((String)"#FF5722", (double)0.95));
        gc.setLineWidth(3.0);
        gc.strokeLine(fromX, fromY, toX, toY);
        double ux = dx / len;
        double uy = dy / len;
        double ax = toX - ux * 12.0;
        double ay = toY - uy * 12.0;
        double nx = -uy * 8.0;
        double ny = ux * 8.0;
        gc.setFill((Paint)Color.web((String)"#FF5722", (double)0.95));
        gc.fillPolygon(new double[]{toX, ax + nx, ax - nx}, new double[]{toY, ay + ny, ay - ny}, 3);
    }

    static void drawSelectionRect(Canvas canvas, double x1, double y1, double x2, double y2) {
        if (canvas == null) {
            return;
        }
        double x = Math.min(x1, x2);
        double y = Math.min(y1, y2);
        double w = Math.abs(x2 - x1);
        double h = Math.abs(y2 - y1);
        if (w <= 0.0 || h <= 0.0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill((Paint)Color.web((String)"#2196F3", (double)0.18));
        gc.fillRect(x, y, w, h);
        gc.setStroke((Paint)Color.web((String)"#1976D2"));
        gc.setLineWidth(2.5);
        gc.strokeRect(x, y, w, h);
    }

    static void drawDetectedCellGrid(Canvas canvas, List<CellRect> cells) {
        if (canvas == null || cells == null || cells.isEmpty()) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setStroke((Paint)Color.web((String)"#4CAF50", (double)0.85));
        gc.setLineWidth(1.5);
        for (CellRect c : cells) {
            double x = c.getX();
            double y = c.getY();
            double w = c.getWidth();
            double h = c.getHeight();
            if (w <= 0.0 || h <= 0.0) continue;
            gc.strokeRect(x, y, w, h);
        }
    }

    static OcrRow hitTest(List<OcrRow> ocrRows, List<OcrRow> virtualCellRows, double x, double y) {
        OcrRow hit = OcrBoxOverlay.hitTest(virtualCellRows, x, y);
        if (hit != null) {
            return hit;
        }
        return OcrBoxOverlay.hitTest(ocrRows, x, y);
    }

    static OcrRow hitTest(List<OcrRow> ocrRows, double x, double y) {
        if (ocrRows == null || ocrRows.isEmpty()) {
            return null;
        }
        OcrRow best = null;
        double bestArea = Double.MAX_VALUE;
        for (OcrRow row : ocrRows) {
            double area;
            if (row == null || !row.hasBbox() || !OcrBoxOverlay.containsPoint(row, x, y) || !((area = OcrBoxOverlay.boxArea(row)) < bestArea)) continue;
            bestArea = area;
            best = row;
        }
        return best;
    }

    static Canvas buildAnnotatedCanvas(Image image, List<OcrRow> ocrRows) {
        Canvas canvas = OcrBoxOverlay.createCanvas(image);
        OcrBoxOverlay.drawImage(canvas.getGraphicsContext2D(), image);
        OcrBoxOverlay.drawBoxes(canvas.getGraphicsContext2D(), ocrRows);
        return canvas;
    }

    static Canvas buildSlotOverlayCanvas(Image image, List<SlotValue> slots) {
        Canvas canvas = OcrBoxOverlay.createCanvas(image);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        OcrBoxOverlay.drawImage(gc, image);
        OcrBoxOverlay.drawSlotPairs(gc, slots);
        return canvas;
    }

    static Canvas buildPlainCanvas(Image image) {
        Canvas canvas = OcrBoxOverlay.createCanvas(image);
        OcrBoxOverlay.drawImage(canvas.getGraphicsContext2D(), image);
        return canvas;
    }

    private static Canvas createCanvas(Image image) {
        return new Canvas(image.getWidth(), image.getHeight());
    }

    private static void drawImage(GraphicsContext gc, Image image) {
        gc.drawImage(image, 0.0, 0.0, image.getWidth(), image.getHeight());
    }

    private static void drawBoxes(GraphicsContext gc, List<OcrRow> ocrRows) {
        if (ocrRows == null || ocrRows.isEmpty()) {
            return;
        }
        gc.setStroke((Paint)BOX_STROKE);
        gc.setLineWidth(2.5);
        for (OcrRow row : ocrRows) {
            OcrBoxOverlay.strokeRowFx(gc, row, BOX_STROKE, 2.5, null);
        }
    }

    private static void drawSlotPairs(GraphicsContext gc, List<SlotValue> slots) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        gc.setLineWidth(2.5);
        for (SlotValue slot : slots) {
            OcrRow label = slot.getLabelRow();
            OcrRow value = slot.getValueRow();
            if (label != null && label.hasBbox()) {
                OcrBoxOverlay.strokeRowFx(gc, label, LABEL_STROKE, 2.5, null);
            }
            if (value != null && value.hasBbox()) {
                OcrBoxOverlay.strokeRowFx(gc, value, VALUE_STROKE, 2.5, null);
            }
            if (label == null || value == null || !label.hasBbox() || !value.hasBbox()) continue;
            gc.setStroke((Paint)LINK_STROKE);
            gc.strokeLine(label.centerX(), label.centerY(), value.centerX(), value.centerY());
        }
    }

    private static void strokeRow(Graphics2D g, OcrRow row) {
        if (row == null || !row.hasBbox()) {
            return;
        }
        double x = row.getStartX();
        double y = row.getStartY();
        double w = row.getEndX() - x;
        double h = row.getEndY() - y;
        if (w > 0.0 && h > 0.0) {
            g.drawRect((int)x, (int)y, (int)w, (int)h);
        }
    }

    private static boolean isHighlighted(OcrRow row, List<OcrRow> highlightRows) {
        if (highlightRows == null || highlightRows.isEmpty() || row == null) {
            return false;
        }
        for (OcrRow highlight : highlightRows) {
            if (highlight != row) continue;
            return true;
        }
        return false;
    }

    private static boolean containsPoint(OcrRow row, double x, double y) {
        return x >= row.getStartX() && x <= row.getEndX() && y >= row.getStartY() && y <= row.getEndY();
    }

    private static double boxArea(OcrRow row) {
        return Math.max(0.0, row.getEndX() - row.getStartX()) * Math.max(0.0, row.getEndY() - row.getStartY());
    }

    private static void strokeVirtualCellFx(GraphicsContext gc, OcrRow row, boolean highlight) {
        if (row == null || !row.hasBbox()) {
            return;
        }
        double x = row.getStartX();
        double y = row.getStartY();
        double w = row.getEndX() - x;
        double h = row.getEndY() - y;
        if (w <= 0.0 || h <= 0.0) {
            return;
        }
        if (highlight) {
            gc.setFill((Paint)VIRTUAL_HIGHLIGHT_FILL);
            gc.setStroke((Paint)VIRTUAL_HIGHLIGHT_STROKE);
            gc.setLineWidth(3.0);
        } else {
            gc.setFill((Paint)VIRTUAL_CELL_FILL);
            gc.setStroke((Paint)VIRTUAL_CELL_STROKE);
            gc.setLineWidth(2.5);
        }
        gc.fillRect(x, y, w, h);
        gc.setLineDashes(new double[]{6.0, 6.0});
        gc.strokeRect(x, y, w, h);
        gc.setLineDashes(null);
    }

    private static void strokeRowFx(GraphicsContext gc, OcrRow row, Color stroke, double lineWidth, Color fill) {
        if (row == null || !row.hasBbox()) {
            return;
        }
        double x = row.getStartX();
        double y = row.getStartY();
        double w = row.getEndX() - x;
        double h = row.getEndY() - y;
        if (w <= 0.0 || h <= 0.0) {
            return;
        }
        if (fill != null) {
            gc.setFill((Paint)fill);
            gc.fillRect(x, y, w, h);
        }
        gc.setStroke((Paint)stroke);
        gc.setLineWidth(lineWidth);
        gc.strokeRect(x, y, w, h);
    }

    /**
     * 绘制输出字段编号列表 (可点击绑定)。
     * @return 每个标签行的点击区域 [{x,y,w,h}, ...]
     */
    static List<double[]> drawSlotLabels(Canvas canvas, double anchorX, double anchorY, List<String> labels) {
        return drawSlotLabelsBelow(canvas, anchorX, anchorY, anchorY, labels, 1.0);
    }

    /**
     * 画在选区下方；字号按当前缩放反算，使屏上大约保持可读字号。
     * @param zoom 预览当前缩放（content scale）
     */
    static List<double[]> drawSlotLabelsBelow(Canvas canvas,
                                              double regionLeft, double regionTop, double regionBottom,
                                              List<String> labels, double zoom) {
        if (canvas == null || labels == null || labels.isEmpty()) {
            return Collections.emptyList();
        }
        double z = Math.max(0.05, zoom);
        // 目标屏上约 18px 字号；画布随缩放缩小，故画布字号 = 屏上目标 / zoom
        double fontSize = Math.max(18.0, 18.0 / z);
        double lineH = fontSize * 1.55;
        double padX = fontSize * 0.7;
        double padY = fontSize * 0.45;
        double gap = Math.max(10.0, 12.0 / z);

        Font font = Font.font("Microsoft YaHei", FontWeight.BOLD, fontSize);
        javafx.scene.text.Text probe = new javafx.scene.text.Text();
        probe.setFont(font);
        double maxW = fontSize * 4;
        for (String label : labels) {
            probe.setText(label == null ? "" : label);
            double w = probe.getLayoutBounds().getWidth();
            if (w > maxW) maxW = w;
        }
        // 略留余量；上限约为可视高度相关，但不要过窄
        double bgW = Math.min(canvas.getWidth() * 0.9, maxW + padX * 2 + fontSize);
        double bgH = labels.size() * lineH + padY * 2;

        double bx = regionLeft;
        if (bx + bgW > canvas.getWidth() - 4) {
            bx = Math.max(4, canvas.getWidth() - bgW - 4);
        }
        if (bx < 4) bx = 4;

        double by = regionBottom + gap;
        if (by + bgH > canvas.getHeight() - 4) {
            by = regionTop - gap - bgH;
        }
        if (by < 4) by = 4;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFont(font);
        gc.setFill(Color.web("#111", 0.92));
        gc.fillRoundRect(bx, by, bgW, bgH, Math.max(6, fontSize * 0.25), Math.max(6, fontSize * 0.25));
        gc.setStroke(Color.web("#4CAF50", 0.95));
        gc.setLineWidth(Math.max(2.0, 2.0 / z));
        gc.strokeRoundRect(bx, by, bgW, bgH, Math.max(6, fontSize * 0.25), Math.max(6, fontSize * 0.25));

        ArrayList<double[]> hitBoxes = new ArrayList<>();
        gc.setFill(Color.WHITE);
        double baseline = fontSize * 0.85;
        for (int i = 0; i < labels.size(); i++) {
            double rowY = by + padY + i * lineH;
            gc.fillText(labels.get(i), bx + padX, rowY + baseline);
            hitBoxes.add(new double[]{bx, rowY, bgW, lineH});
        }
        return hitBoxes;
    }

    public static void drawCellGrid(Canvas canvas, List<CellRect> cells) {
        if (canvas == null || cells == null || cells.isEmpty()) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setStroke((Paint)Color.web((String)"#4CAF50", (double)0.7));
        gc.setLineWidth(1.5);
        gc.setLineDashes(new double[]{4.0, 4.0});
        for (CellRect cell : cells) {
            gc.strokeRect(cell.getX(), cell.getY(), cell.getWidth(), cell.getHeight());
        }
        gc.setLineDashes(null);
    }

    public static void drawIndexLabels(Canvas canvas, List<OcrRow> ocrRows, int startIndex) {
        if (canvas == null || ocrRows == null || ocrRows.isEmpty()) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFont(Font.font((String)"SansSerif", (FontWeight)FontWeight.BOLD, (double)9.0));
        for (int i = 0; i < ocrRows.size(); ++i) {
            OcrRow row = ocrRows.get(i);
            if (!row.hasBbox()) continue;
            String label = String.valueOf(startIndex + i);
            double x = row.getEndX() + 2.0;
            double y = row.getStartY();
            double tw = label.length() * 6 + 6;
            gc.setFill((Paint)Color.web((String)"#FFEB3B", (double)0.92));
            gc.fillRect(x, y, tw, 12.0);
            gc.setFill((Paint)Color.web((String)"#333"));
            gc.fillText(label, x + 2.0, y + 10.0);
        }
    }

}
