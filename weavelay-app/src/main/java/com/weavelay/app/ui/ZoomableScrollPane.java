package com.weavelay.app.ui;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.transform.Scale;

/**
 * 可缩放滚动的页图容器: 外框尺寸由布局决定, 仅内容缩放.
 */
final class ZoomableScrollPane extends ScrollPane {

    private static final double MIN_ZOOM = 0.05;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_STEP = 1.15;

    private final Group contentRoot = new Group();
    private final Pane scrollContent = new Pane(contentRoot);
    private double userZoom = 1.0;
    private double imageWidth;
    private double imageHeight;
    private long contentSeq;
    private Runnable zoomChangedHandler;

    ZoomableScrollPane() {
        setContent(scrollContent);
        setFitToWidth(false);
        setFitToHeight(false);
        setPannable(true);
        setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("app-preview-bg");

        addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown()) {
                return;
            }
            event.consume();
            if (event.getDeltaY() > 0) {
                zoomIn();
            } else if (event.getDeltaY() < 0) {
                zoomOut();
            }
        });

        widthProperty().addListener((obs, oldW, newW) -> {
            if (imageWidth > 0 && userZoom <= 0 && newW.doubleValue() > 0) {
                applyScale();
            }
        });
    }

    void setPageContent(Node node, double width, double height) {
        imageWidth = width > 0 ? width : 1;
        imageHeight = height > 0 ? height : 1;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applyPageContent(node));
            return;
        }
        applyPageContent(node);
    }

    private void applyPageContent(Node node) {
        contentSeq++;
        contentRoot.getChildren().clear();
        contentRoot.getTransforms().clear();
        if (node != null) {
            contentRoot.getChildren().add(node);
        }
        applyScale();
    }

    boolean hasPageContent() {
        return imageWidth > 0 && !contentRoot.getChildren().isEmpty();
    }

    void clearContent() {
        contentSeq++;
        contentRoot.getChildren().clear();
        contentRoot.getTransforms().clear();
        imageWidth = 0;
        imageHeight = 0;
        userZoom = 1.0;
        scrollContent.setMinSize(0, 0);
        scrollContent.setPrefSize(0, 0);
    }

    void zoomIn() {
        setUserZoom(userZoom > 0 ? userZoom * ZOOM_STEP : fitWidthScale() * ZOOM_STEP);
    }

    void zoomOut() {
        setUserZoom(userZoom > 0 ? userZoom / ZOOM_STEP : fitWidthScale() / ZOOM_STEP);
    }

    void zoomReset() {
        setUserZoom(1.0);
    }

    void zoomFitWidth() {
        userZoom = 0;
        applyScale();
        notifyZoomChanged();
    }

    double getUserZoom() {
        return userZoom > 0 ? userZoom : fitWidthScale();
    }

    /** 0 = 适应宽度；>0 = 用户设定倍数。 */
    double getRawZoom() {
        return userZoom;
    }

    void setOnZoomChanged(Runnable handler) {
        this.zoomChangedHandler = handler;
    }

    public void setUserZoom(double zoom) {
        userZoom = clamp(zoom, MIN_ZOOM, MAX_ZOOM);
        applyScale();
        notifyZoomChanged();
    }

    private void notifyZoomChanged() {
        if (zoomChangedHandler != null) {
            zoomChangedHandler.run();
        }
    }

    private void applyScale() {
        if (imageWidth <= 0 || imageHeight <= 0 || contentRoot.getChildren().isEmpty()) {
            contentRoot.getTransforms().clear();
            scrollContent.setMinSize(0, 0);
            scrollContent.setPrefSize(0, 0);
            return;
        }
        double scale = userZoom > 0 ? userZoom : fitWidthScale();
        contentRoot.getTransforms().setAll(new Scale(scale, scale, 0, 0));
        double contentW = imageWidth * scale;
        double contentH = imageHeight * scale;
        scrollContent.setMinSize(contentW, contentH);
        scrollContent.setPrefSize(contentW, contentH);
        scrollContent.setMaxSize(contentW, contentH);
    }

    private double fitWidthScale() {
        double viewportW = getViewportBounds().getWidth();
        if (viewportW <= 1) {
            viewportW = getWidth();
            if (viewportW <= 1) {
                viewportW = getPrefWidth();
            }
        }
        if (viewportW <= 1 || imageWidth <= 0) {
            return 1.0;
        }
        return clamp(viewportW / imageWidth, MIN_ZOOM, MAX_ZOOM);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
