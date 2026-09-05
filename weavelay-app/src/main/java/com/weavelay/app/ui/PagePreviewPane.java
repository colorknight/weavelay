package com.weavelay.app.ui;

import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.WeavePageResult;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.stage.Popup;
import javafx.event.EventHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 织版主视图: PDF 预览 + 框选绑定.
 *
 * <p>框选区域后弹出固定字号的输出字段列表（UI 浮层，不随 PDF 缩放），点击即可绑定.
 * Esc 或右键取消框选.</p>
 */
public final class PagePreviewPane extends BorderPane {

    private final ZoomableScrollPane scroll = new ZoomableScrollPane();
    private final Label zoomLabel = new Label("-");
    private final HBox zoomBar;
    private final Region zoomBarSpacer;
    /** 0 = 适应宽度；缺省 0.3 与原先写死倍数一致。 */
    private double rememberedZoom = 0.3;
    private java.util.function.DoubleConsumer zoomChangeHandler;
    private boolean applyingZoom;
    private Canvas overlayCanvas;
    private List<OcrRow> currentOcrRows = Collections.emptyList();
    private List<OcrRow> virtualCellRows = Collections.emptyList();
    private List<OcrRow> currentHighlightRows = Collections.emptyList();
    /** 选中字段时高亮旁的可编辑输出值（主窗内浮层，不随 PDF 缩放）。 */
    private String valueChipText;
    private String valueChipSlotCode;
    private OcrRow valueChipAnchor;
    private boolean valueChipCommitting;
    private boolean valueChipEditing;
    /** true = 表格格旁白（双击走 tableCellEditHandler）。 */
    private boolean valueChipTableMode;
    private int valueChipTableDataRow = -1;
    private int valueChipTableDataCol = -1;
    private Consumer<OcrRow> ocrRowClickHandler;
    private Consumer<RectRegion> regionSelectHandler;
    /** false = 应用模式：禁止框选绑定，仅允许点选（含表格虚格）。 */
    private boolean regionSelectEnabled = true;
    private Consumer<Integer> slotBindHandler;
    /** (slotCode, newValue) */
    private java.util.function.BiConsumer<String, String> valueChipEditHandler;
    /** (slotCode, dataRow, dataCol, newValue) */
    private TableCellEditHandler valueChipTableCellEditHandler;
    /** 右键取消旁白/选中 */
    private Runnable valueChipDismissHandler;

    @FunctionalInterface
    public interface TableCellEditHandler {
        void onEdit(String slotCode, int dataRow, int dataCol, String newValue);
    }
    private java.util.function.BiConsumer<Integer, Integer> rowRangeHandler;
    private List<com.weavelay.core.layout.CellRect> currentCells = Collections.emptyList();
    private int lastClickedRowIndex = -1;

    // 框选状态
    private boolean dragging = false;
    private double selStartX, selStartY, selEndX, selEndY;
    private boolean hasSelection = false;

    private List<String> slotLabels = Collections.emptyList();

    /** 浮在 PDF 上的输出选择列表（固定 UI 字号，不随页面缩放）。 */
    private final Popup slotPickerPopup = new Popup();
    private final ListView<String> slotPickerList = new ListView<>();
    /** 预览栈：滚动页 + 可编辑对照框（同一窗口内，不会漂到别的软件上）。 */
    private final StackPane previewStack = new StackPane();
    private final Label valueChipLabel = new Label();
    private final TextField valueChipField = new TextField();
    private final Label valueChipHint = new Label("双击可修改");
    private VBox valueChipBox;
    private Label wheelHintLabel;
    private final EventHandler<KeyEvent> valueChipEscFilter = e -> {
        if (valueChipEditing && e.getCode() == KeyCode.ESCAPE) {
            endValueChipEdit(false);
            e.consume();
        }
    };
    private static final String CHIP_FACE_STYLE =
            "-fx-font-family: 'Microsoft YaHei'; "
                    + "-fx-font-size: 22px; "
                    + "-fx-font-weight: bold; "
                    + "-fx-text-fill: #BF360C; "
                    + "-fx-background-color: #FFF8E1; "
                    + "-fx-border-color: #E65100; "
                    + "-fx-border-width: 2.5; "
                    + "-fx-background-radius: 8; "
                    + "-fx-border-radius: 8; "
                    + "-fx-padding: 10 16;";

    public PagePreviewPane() {
        Button zoomOut = new Button("-");
        Button zoomIn = new Button("+");
        Button zoomFit = new Button("适应宽度");
        Button zoom100 = new Button("100%");
        zoomOut.getStyleClass().add("app-btn");
        zoomIn.getStyleClass().add("app-btn");
        zoomFit.getStyleClass().add("app-btn");
        zoom100.getStyleClass().add("app-btn");
        zoomOut.setOnAction(e -> zoomOut());
        zoomIn.setOnAction(e -> zoomIn());
        zoomFit.setOnAction(e -> zoomFit());
        zoom100.setOnAction(e -> zoomReset());
        scroll.setOnZoomChanged(this::afterZoomChanged);

        Label zoomTitle = new Label("缩放");
        Label wheelHint = new Label("Ctrl+滚轮 · 左键拖拽框选 → 点击列表绑定 · 右键取消");
        zoomTitle.getStyleClass().add("app-label-field");
        wheelHint.getStyleClass().add("app-hint");
        this.wheelHintLabel = wheelHint;

        zoomBarSpacer = new Region();
        HBox.setHgrow(zoomBarSpacer, Priority.ALWAYS);
        zoomBar = new HBox(8, zoomTitle, zoomOut, zoomLabel, zoomIn, zoomFit, zoom100, wheelHint, zoomBarSpacer);
        zoomBar.setAlignment(Pos.CENTER_LEFT);
        zoomBar.setPadding(new Insets(0, 0, 4, 0));

        previewStack.getChildren().add(scroll);
        StackPane.setAlignment(scroll, Pos.TOP_LEFT);
        setupValueChipField();
        VBox.setVgrow(previewStack, Priority.ALWAYS);
        previewStack.setMinHeight(200);
        previewStack.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        scroll.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        VBox box = new VBox(6, zoomBar, previewStack);
        VBox.setVgrow(previewStack, Priority.ALWAYS);
        box.setMinHeight(200);
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        setCenter(box);
        setPadding(new Insets(0, 4, 8, 8));

        setupSlotPicker();
        // 滚动/缩放后同步对照框位置
        scroll.hvalueProperty().addListener((o, a, b) -> repositionValueChip());
        scroll.vvalueProperty().addListener((o, a, b) -> repositionValueChip());
        scroll.widthProperty().addListener((o, a, b) -> {
            if (scroll.getRawZoom() <= 0) {
                updateZoomLabel();
            }
            repositionValueChip();
        });
        scroll.heightProperty().addListener((o, a, b) -> repositionValueChip());
        previewStack.widthProperty().addListener((o, a, b) -> repositionValueChip());
        previewStack.heightProperty().addListener((o, a, b) -> repositionValueChip());
        // 字段选择 Popup 仍是独立窗：失焦时藏起
        sceneProperty().addListener((o, oldScene, newScene) -> bindOverlayOwnerWindow(newScene));
        if (getScene() != null) {
            bindOverlayOwnerWindow(getScene());
        }
    }

    /** 缩放栏最右侧追加控件（如页面类型），与缩放按钮同排。 */
    public void setZoomBarTrailing(Node... nodes) {
        if (zoomBar == null) {
            return;
        }
        int spacerIdx = zoomBar.getChildren().indexOf(zoomBarSpacer);
        if (spacerIdx < 0) {
            return;
        }
        zoomBar.getChildren().remove(spacerIdx + 1, zoomBar.getChildren().size());
        if (nodes != null) {
            for (Node n : nodes) {
                if (n != null) {
                    zoomBar.getChildren().add(n);
                }
            }
        }
    }

    /** 0 = 适应宽度。 */
    public void setRememberedZoom(double zoom) {
        rememberedZoom = zoom;
    }

    public double getRememberedZoom() {
        return rememberedZoom;
    }

    public void setZoomChangeHandler(java.util.function.DoubleConsumer handler) {
        this.zoomChangeHandler = handler;
    }

    private void bindOverlayOwnerWindow(javafx.scene.Scene scene) {
        if (scene == null) {
            return;
        }
        scene.windowProperty().addListener((o, oldWin, newWin) -> wireOwnerWindow(newWin));
        wireOwnerWindow(scene.getWindow());
    }

    private void wireOwnerWindow(javafx.stage.Window window) {
        if (window == null) {
            return;
        }
        window.focusedProperty().addListener((obs, was, focused) -> {
            if (!Boolean.TRUE.equals(focused)) {
                if (valueChipEditing) {
                    endValueChipEdit(true);
                }
                hideSlotPicker();
            } else if (valueChipText != null) {
                repositionValueChip();
            }
        });
        if (window instanceof javafx.stage.Stage stage) {
            stage.iconifiedProperty().addListener((obs, was, iconified) -> {
                if (Boolean.TRUE.equals(iconified)) {
                    if (valueChipEditing) {
                        endValueChipEdit(true);
                    }
                    hideSlotPicker();
                    hideValueChip();
                } else if (valueChipText != null && stage.isFocused()) {
                    repositionValueChip();
                }
            });
        }
    }

    private void setupValueChipField() {
        // 不是画在 PDF 上的：主窗里的 UI 控件。展示/编辑同一尺寸叠在同一格里。
        valueChipLabel.setWrapText(true);
        valueChipLabel.setMaxWidth(420);
        valueChipLabel.setStyle(CHIP_FACE_STYLE
                + " -fx-cursor: hand;");
        valueChipLabel.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                if (valueChipDismissHandler != null) {
                    valueChipDismissHandler.run();
                } else {
                    clearValueChip();
                }
                e.consume();
                return;
            }
            if (e.getClickCount() >= 2) {
                beginValueChipEdit();
                e.consume();
            }
        });

        valueChipField.setMaxWidth(420);
        valueChipField.setVisible(false);
        valueChipField.setManaged(false);
        // 与标签同款样式，原位替换，不另开大编辑框
        valueChipField.setStyle(CHIP_FACE_STYLE
                + " -fx-control-inner-background: #FFF8E1;"
                + " -fx-highlight-fill: #FFCC80;"
                + " -fx-highlight-text-fill: #BF360C;");
        valueChipHint.setStyle(
                "-fx-font-size: 11px; -fx-text-fill: #E65100; "
                        + "-fx-background-color: rgba(255,248,225,0.92); -fx-padding: 1 6;");

        StackPane face = new StackPane(valueChipLabel, valueChipField);
        face.setMaxWidth(420);
        valueChipBox = new VBox(2, face, valueChipHint);
        valueChipBox.setManaged(false);
        valueChipBox.setVisible(false);
        previewStack.getChildren().add(valueChipBox);
        StackPane.setAlignment(valueChipBox, Pos.TOP_LEFT);

        valueChipField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                endValueChipEdit(true);
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                endValueChipEdit(false);
                e.consume();
            }
        });
        valueChipField.focusedProperty().addListener((o, was, focused) -> {
            if (!Boolean.TRUE.equals(focused) && valueChipEditing && !valueChipCommitting) {
                endValueChipEdit(true);
            }
        });
    }

    public void setValueChipEditHandler(java.util.function.BiConsumer<String, String> handler) {
        this.valueChipEditHandler = handler;
    }

    public void setValueChipDismissHandler(Runnable handler) {
        this.valueChipDismissHandler = handler;
    }

    public void setTableCellEditHandler(TableCellEditHandler handler) {
        this.valueChipTableCellEditHandler = handler;
    }

    private void beginValueChipEdit() {
        if (valueChipEditing || valueChipText == null) {
            return;
        }
        if (valueChipTableMode && (valueChipTableCellEditHandler == null
                || valueChipSlotCode == null || valueChipSlotCode.isBlank()
                || valueChipTableDataRow < 0 || valueChipTableDataCol < 0)) {
            return;
        }
        if (!valueChipTableMode && (valueChipEditHandler == null
                || valueChipSlotCode == null || valueChipSlotCode.isBlank())) {
            return;
        }
        valueChipEditing = true;
        // 先量好标签尺寸，编辑框锁成一样大
        valueChipLabel.applyCss();
        valueChipLabel.autosize();
        double w = Math.max(80, valueChipLabel.getBoundsInLocal().getWidth());
        double h = Math.max(36, valueChipLabel.getBoundsInLocal().getHeight());
        valueChipField.setPrefSize(w, h);
        valueChipField.setMinSize(w, h);
        valueChipField.setMaxSize(Math.max(w, 420), h);
        valueChipField.setText(valueChipText.replace('\n', ' '));
        valueChipLabel.setVisible(false);
        valueChipField.setVisible(true);
        valueChipField.setManaged(true);
        valueChipHint.setText("Enter 保存 · Esc 取消");
        if (getScene() != null) {
            getScene().addEventFilter(KeyEvent.KEY_PRESSED, valueChipEscFilter);
        }
        valueChipField.requestFocus();
        valueChipField.selectAll();
        repositionValueChip();
    }

    private void endValueChipEdit(boolean save) {
        if (!valueChipEditing) {
            return;
        }
        // 先清编辑态，避免失焦监听再当成「保存」把 Esc 取消冲掉
        valueChipEditing = false;
        if (getScene() != null) {
            getScene().removeEventFilter(KeyEvent.KEY_PRESSED, valueChipEscFilter);
        }
        if (save) {
            commitValueChipEditFromField();
        } else {
            valueChipField.setText(valueChipText == null ? "" : valueChipText.replace('\n', ' '));
        }
        valueChipField.setVisible(false);
        valueChipField.setManaged(false);
        valueChipLabel.setVisible(true);
        valueChipLabel.setText(displayChipText(valueChipText));
        valueChipHint.setText("双击可修改");
        previewStack.requestFocus();
        repositionValueChip();
    }

    private void commitValueChipEditFromField() {
        if (valueChipCommitting) {
            return;
        }
        String typed = valueChipField.getText();
        if (typed == null) {
            typed = "";
        }
        String prev = valueChipText == null ? "" : valueChipText;
        if (typed.equals(prev) || typed.equals(prev.replace('\n', ' '))) {
            valueChipText = typed;
            return;
        }
        valueChipCommitting = true;
        try {
            if (valueChipTableMode) {
                if (valueChipTableCellEditHandler == null || valueChipSlotCode == null) {
                    return;
                }
                valueChipText = typed;
                valueChipTableCellEditHandler.onEdit(
                        valueChipSlotCode, valueChipTableDataRow, valueChipTableDataCol, typed);
            } else {
                if (valueChipEditHandler == null || valueChipSlotCode == null
                        || valueChipSlotCode.isBlank()) {
                    return;
                }
                String code = valueChipSlotCode;
                valueChipText = typed;
                valueChipEditHandler.accept(code, typed);
            }
        } finally {
            valueChipCommitting = false;
        }
    }

    private String displayChipText(String text) {
        if (text == null) {
            return "";
        }
        // 旁白展示也走一遍归一化，修旧结果里的 α13→⌀13
        String t = com.weavelay.core.formula.LatexToPlainText.convert(text).trim();
        int max = 48;
        if (t.length() > max) {
            return t.substring(0, max - 1) + "…";
        }
        return t;
    }

    private void setupSlotPicker() {
        slotPickerList.setFocusTraversable(false);
        slotPickerList.setStyle(
                "-fx-font-family: 'Microsoft YaHei'; "
                        + "-fx-font-size: 15px; "
                        + "-fx-background-color: #1a1a1a; "
                        + "-fx-control-inner-background: #1a1a1a; "
                        + "-fx-text-fill: white; "
                        + "-fx-border-color: #4CAF50; "
                        + "-fx-border-width: 1.5; "
                        + "-fx-background-radius: 6; "
                        + "-fx-border-radius: 6;");
        slotPickerList.setPrefWidth(280);
        slotPickerList.setFixedCellSize(32);
        slotPickerList.setOnMouseClicked(e -> {
            int idx = slotPickerList.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && slotBindHandler != null) {
                hideSlotPicker();
                slotBindHandler.accept(idx);
            }
        });
        slotPickerPopup.getContent().add(slotPickerList);
        slotPickerPopup.setAutoHide(false);
    }

    public List<OcrRow> getCurrentOcrRows() {
        return currentOcrRows == null ? Collections.emptyList() : currentOcrRows;
    }

    public void setOcrRowClickHandler(Consumer<OcrRow> handler) {
        this.ocrRowClickHandler = handler;
    }

    public void setRegionSelectHandler(Consumer<RectRegion> handler) {
        this.regionSelectHandler = handler;
    }

    /**
     * 模板模式 true：可框选绑定；应用模式 false：只点选（表格绿格 / 字段高亮），不弹绑定列表。
     */
    public void setRegionSelectEnabled(boolean enabled) {
        this.regionSelectEnabled = enabled;
        if (!enabled) {
            hasSelection = false;
            dragging = false;
            hideSlotPicker();
            setSlotLabels(null);
            redrawOverlay();
        }
        if (wheelHintLabel != null) {
            wheelHintLabel.setText(enabled
                    ? "Ctrl+滚轮 · 左键拖拽框选 → 点击列表绑定 · 右键取消"
                    : "Ctrl+滚轮 · 点右侧字段高亮 · 点表格绿格看旁白 · 右键取消");
        }
    }

    /** 点击输出字段列表项时的回调, idx 为顶层输出索引. */
    public void setSlotBindHandler(Consumer<Integer> handler) {
        this.slotBindHandler = handler;
    }

    public void setSlotLabels(List<String> labels) {
        this.slotLabels = labels == null ? Collections.emptyList() : labels;
        if (hasSelection && !dragging && !slotLabels.isEmpty()) {
            showSlotPicker();
        } else {
            hideSlotPicker();
        }
    }

    public void showPage(WeavePageResult page, int index, int total) {
        if (page == null) {
            clear();
            return;
        }
        hideSlotPicker();
        clearValueChip();
        currentOcrRows = Collections.emptyList();
        virtualCellRows = Collections.emptyList();
        currentHighlightRows = Collections.emptyList();
        currentCells = Collections.emptyList();
        hasSelection = false;
        if (scroll.hasPageContent()) {
            rememberedZoom = scroll.getRawZoom();
        }
        Image image = PageImageFactory.loadSync(page.getPagePng());
        javafx.scene.layout.Pane stack = OcrBoxOverlay.buildInteractivePreview(
                page.getPagePng(), Collections.emptyList(), image.getWidth(), image.getHeight());
        overlayCanvas = extractOverlayCanvas(stack);
        wireOverlayClicks(overlayCanvas);
        wireRegionSelect(overlayCanvas);
        scroll.setPageContent(stack, image.getWidth(), image.getHeight());
        setHighlightRows(null);
        restoreZoom();
    }

    public void setVirtualCellRows(List<OcrRow> rows) {
        virtualCellRows = rows == null ? Collections.emptyList() : rows;
        redrawOverlay();
    }

    public void setHighlightRows(List<OcrRow> highlightRows) {
        currentHighlightRows = highlightRows == null ? Collections.emptyList() : highlightRows;
        redrawOverlay();
    }

    public void addOcrRows(List<OcrRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        if (currentOcrRows.isEmpty()) {
            currentOcrRows = new ArrayList<>();
        } else if (!(currentOcrRows instanceof ArrayList)) {
            currentOcrRows = new ArrayList<>(currentOcrRows);
        }
        ((ArrayList<OcrRow>) currentOcrRows).addAll(rows);
        redrawOverlay();
    }

    public void clearHighlight() {
        clearValueChip();
        setHighlightRows(null);
    }

    /**
     * 在高亮框旁显示可编辑输出值（主窗内浮层，固定字号，不随 PDF 缩放）。
     */
    public void setValueChip(String text, OcrRow anchor, String slotCode) {
        // 先结束上一字段编辑并写回（仍用旧 slotCode）
        if (valueChipEditing) {
            endValueChipEdit(true);
        }
        if (anchor == null || !anchor.hasBbox() || slotCode == null || slotCode.isBlank()) {
            valueChipText = null;
            valueChipSlotCode = null;
            valueChipAnchor = null;
            valueChipTableMode = false;
            valueChipTableDataRow = -1;
            valueChipTableDataCol = -1;
            hideValueChip();
            return;
        }
        valueChipTableMode = false;
        valueChipTableDataRow = -1;
        valueChipTableDataCol = -1;
        valueChipText = text == null ? "" : text;
        valueChipSlotCode = slotCode;
        valueChipAnchor = anchor;
        valueChipEditing = false;
        valueChipField.setVisible(false);
        valueChipField.setManaged(false);
        valueChipLabel.setVisible(true);
        valueChipLabel.setManaged(true);
        valueChipLabel.setMaxWidth(420);
        applyChipFaceStyle();
        valueChipLabel.setText(displayChipText(valueChipText));
        valueChipHint.setText("双击可修改 · 右键取消");
        repositionValueChip();
    }

    /**
     * 表格格对照：样式与标量旁白一致，双击可改并写回表格单元格。
     */
    public void setTableRowChip(
            String text, OcrRow anchor, String slotCode, int dataRow, int dataCol) {
        if (valueChipEditing) {
            endValueChipEdit(true);
        }
        if (anchor == null || !anchor.hasBbox() || slotCode == null || slotCode.isBlank()) {
            valueChipText = null;
            valueChipSlotCode = null;
            valueChipAnchor = null;
            valueChipTableMode = false;
            valueChipTableDataRow = -1;
            valueChipTableDataCol = -1;
            hideValueChip();
            return;
        }
        valueChipTableMode = dataRow >= 0 && dataCol >= 0;
        valueChipSlotCode = slotCode;
        valueChipTableDataRow = dataRow;
        valueChipTableDataCol = dataCol;
        valueChipText = text == null ? "" : text;
        valueChipAnchor = anchor;
        valueChipEditing = false;
        valueChipField.setVisible(false);
        valueChipField.setManaged(false);
        valueChipLabel.setVisible(true);
        valueChipLabel.setManaged(true);
        valueChipLabel.setMaxWidth(420);
        applyChipFaceStyle();
        valueChipLabel.setText(displayChipText(valueChipText));
        valueChipHint.setText("双击可修改 · 右键取消");
        repositionValueChip();
    }

    private void applyChipFaceStyle() {
        valueChipLabel.setStyle(CHIP_FACE_STYLE
                + " -fx-cursor: hand;");
        valueChipHint.setStyle(
                "-fx-font-size: 11px; -fx-text-fill: #E65100; "
                        + "-fx-background-color: rgba(255,248,225,0.92); -fx-padding: 1 6;");
    }

    public void clearValueChip() {
        if (valueChipEditing) {
            endValueChipEdit(true);
        }
        valueChipText = null;
        valueChipSlotCode = null;
        valueChipAnchor = null;
        valueChipEditing = false;
        valueChipTableMode = false;
        valueChipTableDataRow = -1;
        valueChipTableDataCol = -1;
        hideValueChip();
    }

    /** 确认写 JSON 前：若旁白正在编辑，先提交。 */
    public void flushValueChipEdit() {
        if (valueChipEditing) {
            endValueChipEdit(true);
        }
    }

    public String getValueChipSlotCode() {
        return valueChipSlotCode;
    }

    public String getValueChipText() {
        return valueChipText;
    }

    private void hideValueChip() {
        if (valueChipEditing) {
            if (getScene() != null) {
                getScene().removeEventFilter(KeyEvent.KEY_PRESSED, valueChipEscFilter);
            }
            valueChipEditing = false;
        }
        if (valueChipBox != null) {
            valueChipBox.setVisible(false);
        }
    }

    /** 贴在高亮框旁（previewStack 坐标），字号固定不随 PDF 缩放。 */
    private void repositionValueChip() {
        if (valueChipBox == null) {
            return;
        }
        if (valueChipText == null || valueChipAnchor == null
                || !valueChipAnchor.hasBbox() || overlayCanvas == null) {
            valueChipBox.setVisible(false);
            return;
        }
        if (!valueChipEditing) {
            String shown = displayChipText(valueChipText);
            valueChipLabel.setText(shown.isEmpty() ? "（空）" : shown);
        }

        double ax1 = Math.min(valueChipAnchor.getStartX(), valueChipAnchor.getEndX());
        double ay1 = Math.min(valueChipAnchor.getStartY(), valueChipAnchor.getEndY());
        double ax2 = Math.max(valueChipAnchor.getStartX(), valueChipAnchor.getEndX());
        double ay2 = Math.max(valueChipAnchor.getStartY(), valueChipAnchor.getEndY());

        Point2D rightInStack = overlayCanvas.localToScene(ax2 + 8, ay1);
        Point2D stackOrigin = previewStack.localToScene(0, 0);
        if (rightInStack == null || stackOrigin == null) {
            valueChipBox.setVisible(false);
            return;
        }
        double x = rightInStack.getX() - stackOrigin.getX();
        double y = rightInStack.getY() - stackOrigin.getY();

        valueChipBox.applyCss();
        valueChipBox.autosize();
        double chipW = Math.max(120, valueChipBox.prefWidth(-1));
        double chipH = Math.max(48, valueChipBox.prefHeight(-1));
        double maxX = previewStack.getWidth() - chipW - 4;
        double maxY = previewStack.getHeight() - chipH - 4;
        if (x > maxX) {
            Point2D below = overlayCanvas.localToScene(ax1, ay2 + 8);
            if (below != null) {
                x = below.getX() - stackOrigin.getX();
                y = below.getY() - stackOrigin.getY();
            }
        }
        x = Math.max(4, Math.min(x, Math.max(4, maxX)));
        y = Math.max(4, Math.min(y, Math.max(4, maxY)));

        valueChipBox.relocate(x, y);
        valueChipBox.setVisible(true);
        valueChipBox.toFront();
    }

    public boolean hasPendingSelection() { return hasSelection && !dragging; }

    public void clearPendingSelection() {
        hasSelection = false;
        dragging = false;
        hideSlotPicker();
        redrawOverlay();
    }

    private void redrawOverlay() {
        OcrBoxOverlay.redrawOverlay(
                overlayCanvas,
                currentOcrRows,
                virtualCellRows,
                currentHighlightRows.isEmpty() ? null : currentHighlightRows);
        if (!currentOcrRows.isEmpty()) {
            OcrBoxOverlay.drawIndexLabels(overlayCanvas, currentOcrRows, 1);
        }
        if (!currentCells.isEmpty()) {
            OcrBoxOverlay.drawCellGrid(overlayCanvas, currentCells);
        }
        if (hasSelection) {
            OcrBoxOverlay.drawSelectionRect(overlayCanvas,
                    selStartX, selStartY, selEndX, selEndY);
        }
        // 输出值 / 字段列表：屏幕浮层，不画在 PDF 画布上
        repositionValueChip();
        if (hasSelection && !dragging && !slotLabels.isEmpty()) {
            showSlotPicker();
        } else {
            hideSlotPicker();
        }
    }

    private void showSlotPicker() {
        if (overlayCanvas == null || slotLabels.isEmpty() || !hasSelection || dragging) {
            hideSlotPicker();
            return;
        }
        slotPickerList.getItems().setAll(slotLabels);
        int rows = Math.min(slotLabels.size(), 12);
        slotPickerList.setPrefHeight(rows * 32 + 8);
        slotPickerList.setMaxHeight(12 * 32 + 8);

        double imgLeft = Math.min(selStartX, selEndX);
        double imgBottom = Math.max(selStartY, selEndY);
        Point2D screen = overlayCanvas.localToScreen(imgLeft, imgBottom);
        if (screen == null) {
            hideSlotPicker();
            return;
        }
        double x = screen.getX();
        double y = screen.getY() + 8;
        // 尽量不跑出屏幕右侧
        javafx.stage.Window owner = overlayCanvas.getScene() != null
                ? overlayCanvas.getScene().getWindow() : null;
        if (owner != null) {
            double maxX = owner.getX() + owner.getWidth() - slotPickerList.getPrefWidth() - 8;
            if (x > maxX) x = Math.max(owner.getX() + 8, maxX);
            double maxY = owner.getY() + owner.getHeight() - slotPickerList.getPrefHeight() - 8;
            if (y > maxY) {
                // 下方放不下则翻到选区上方
                Point2D top = overlayCanvas.localToScreen(
                        imgLeft, Math.min(selStartY, selEndY));
                if (top != null) {
                    y = top.getY() - slotPickerList.getPrefHeight() - 8;
                }
            }
        }
        if (!slotPickerPopup.isShowing()) {
            slotPickerPopup.show(overlayCanvas, x, y);
        } else {
            slotPickerPopup.setX(x);
            slotPickerPopup.setY(y);
        }
    }

    private void hideSlotPicker() {
        if (slotPickerPopup.isShowing()) {
            slotPickerPopup.hide();
        }
    }

    // ---- 框选交互 ----

    private void wireRegionSelect(Canvas canvas) {
        if (canvas == null) return;
        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
    }

    private void onMousePressed(MouseEvent e) {
        if (e.isSecondaryButtonDown()) {
            hasSelection = false;
            hideSlotPicker();
            redrawOverlay();
            if (regionSelectHandler != null) regionSelectHandler.accept(null);
            return;
        }
        if (!e.isPrimaryButtonDown()) return;
        if (!regionSelectEnabled) {
            // 框选关闭时：不进入拖拽，留给 click 点绿格/OCR
            return;
        }
        dragging = true;
        hideSlotPicker();
        selStartX = e.getX();
        selStartY = e.getY();
        selEndX = selStartX;
        selEndY = selStartY;
        hasSelection = false;
        e.consume();
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging) return;
        selEndX = Math.max(0, Math.min(e.getX(), overlayCanvas.getWidth()));
        selEndY = Math.max(0, Math.min(e.getY(), overlayCanvas.getHeight()));
        hasSelection = true;
        redrawOverlay();
        e.consume();
    }

    private void onMouseReleased(MouseEvent e) {
        if (!dragging) return;
        dragging = false;
        double x = Math.min(selStartX, selEndX);
        double y = Math.min(selStartY, selEndY);
        double w = Math.abs(selEndX - selStartX);
        double h = Math.abs(selEndY - selStartY);
        if (w < 5 || h < 5) {
            hasSelection = false;
            hideSlotPicker();
            redrawOverlay();
            return;
        }
        selStartX = x;
        selStartY = y;
        selEndX = x + w;
        selEndY = y + h;
        hasSelection = true;
        redrawOverlay();

        if (regionSelectHandler != null) {
            regionSelectHandler.accept(new RectRegion(x, y, w, h));
        }
        e.consume();
    }

    // ---- 点击交互 (OCR 行) ----

    private void wireOverlayClicks(Canvas canvas) {
        if (canvas == null) return;
        canvas.setOnMouseClicked(this::handleOverlayClick);
    }

    private void handleOverlayClick(MouseEvent event) {
        if (overlayCanvas == null) return;
        if (dragging) return;

        // OCR 行 hit test (无框选时)
        if (!hasSelection && ocrRowClickHandler != null) {
            OcrRow hit = OcrBoxOverlay.hitTest(currentOcrRows, virtualCellRows, event.getX(), event.getY());
            if (hit != null) {
                int idx = currentOcrRows.indexOf(hit);
                if (event.isShiftDown() && lastClickedRowIndex >= 0 && rowRangeHandler != null) {
                    int start = Math.min(lastClickedRowIndex, idx);
                    int end = Math.max(lastClickedRowIndex, idx);
                    rowRangeHandler.accept(start + 1, end + 1);
                } else {
                    lastClickedRowIndex = idx;
                    ocrRowClickHandler.accept(hit);
                }
                event.consume();
            }
        }
    }

    public void setRowRangeHandler(java.util.function.BiConsumer<Integer, Integer> handler) {
        this.rowRangeHandler = handler;
    }

    public void setCurrentCells(List<com.weavelay.core.layout.CellRect> cells) {
        this.currentCells = cells == null ? Collections.emptyList() : cells;
        redrawOverlay();
    }

    // ---- 辅助 ----

    private static Canvas extractOverlayCanvas(javafx.scene.layout.Pane pane) {
        if (pane == null) return null;
        for (javafx.scene.Node child : pane.getChildren()) {
            if (child instanceof Canvas) return (Canvas) child;
        }
        return null;
    }

    public void clear() {
        hideSlotPicker();
        clearValueChip();
        overlayCanvas = null;
        currentOcrRows = Collections.emptyList();
        virtualCellRows = Collections.emptyList();
        currentHighlightRows = Collections.emptyList();
        hasSelection = false;
        scroll.clearContent();
        zoomLabel.setText("-");
    }

    private void zoomIn() { scroll.zoomIn(); }
    private void zoomOut() { scroll.zoomOut(); }
    private void zoomFit() { scroll.zoomFitWidth(); }
    private void zoomReset() { scroll.zoomReset(); }

    private void restoreZoom() {
        applyingZoom = true;
        try {
            if (rememberedZoom <= 0) {
                scroll.zoomFitWidth();
            } else {
                scroll.setUserZoom(rememberedZoom);
            }
            updateZoomLabel();
        } finally {
            applyingZoom = false;
        }
    }

    private void afterZoomChanged() {
        if (applyingZoom) {
            updateZoomLabel();
            repositionValueChip();
            return;
        }
        rememberedZoom = scroll.getRawZoom();
        updateZoomLabel();
        repositionValueChip();
        if (zoomChangeHandler != null) {
            zoomChangeHandler.accept(rememberedZoom);
        }
    }

    private void updateZoomLabel() {
        int pct = (int) Math.round(scroll.getUserZoom() * 100);
        zoomLabel.setText(pct + "%");
    }
}
