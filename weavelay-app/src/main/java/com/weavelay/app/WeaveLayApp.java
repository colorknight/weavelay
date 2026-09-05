package com.weavelay.app;

import com.weavelay.app.ui.PageKindPickerBar;
import com.weavelay.app.ui.PagePreviewPane;
import com.weavelay.app.ui.AppIcons;
import com.weavelay.app.ui.IndustrialSymbolPicker;
import com.weavelay.app.ui.ModeSwitch;
import com.weavelay.app.ui.SelectableLabel;
import com.weavelay.app.ui.SettingsButtonFactory;
import com.weavelay.app.ui.SlotTableHelper;
import com.weavelay.app.ui.SlotTablePane;
import com.weavelay.app.ui.OutputConfigCache;
import com.weavelay.app.ui.OutputSettingsDialog;
import com.weavelay.app.ui.RectRegion;
import com.weavelay.app.ui.TableLabelParser;
import com.weavelay.core.layout.CellRect;
import com.weavelay.core.merge.RowMerger;
import com.weavelay.core.model.OcrRow;
import com.weavelay.core.model.SlotValue;
import com.weavelay.core.model.WeavePageResult;
import com.weavelay.ocr.layout.PpDocLayoutBox;
import com.weavelay.core.page.PageKind;
import com.weavelay.core.page.PageKindDefinition;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.formula.FormulaRecognitionService;
import com.weavelay.core.license.LicenseFeatures;
import com.weavelay.core.license.LicenseService;
import com.weavelay.core.license.LicenseStatus;
import com.weavelay.core.store.FamilyRecord;

import com.weavelay.core.store.WeaveLayRuntime;
import com.weavelay.ocr.BatchWeaveService;
import com.weavelay.ocr.OcrParamSettings;
import com.weavelay.ocr.pdf.PdfPageRenderer;
import com.weavelay.ocr.RapidOcrService;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面 UI: 织版主界面 + OCR 诊断 (实施).
 */
public class WeaveLayApp extends Application {

    /** 主区左右分栏：左 8 / 右 2；窗口缩放时强制保持，避免 SplitPane 按像素漂移。 */
    private static final double MAIN_SPLIT_RATIO = 0.8;

    private final RapidOcrService ocrService = new RapidOcrService();
    private final BatchWeaveService batchService = new BatchWeaveService(ocrService);
    /** 与 batchService 共用同一实例（本地 PP-FormulaNet ONNX）. */
    private final FormulaRecognitionService formulaService = batchService.getFormulaService();
    private final AppPreferences preferences = new AppPreferences();
    private final LicenseService licenseService = new LicenseService();
    // 绑定状态
    private RectRegion pendingRegion = null;
    private String pendingDigits = "";
    private List<com.weavelay.core.model.OcrRow> pendingRowGroup = null;
    private int pendingRowStart = 0, pendingRowEnd = 0;

    private AppPreferences.InputKind inputKind;
    /** 当前打开的 PDF 文件。 */
    private Path selectedPath;
    /** 所选文件夹（仅文件夹模式）。 */
    private Path folderPath;

    private final SelectableLabel statusLabel = new SelectableLabel("请选择 PDF 文件夹");
    private final SelectableLabel applyElapsedLabel = new SelectableLabel("");
    private final SelectableLabel folderNameLabel = new SelectableLabel("");
    private final SelectableLabel fileNameLabel = new SelectableLabel("");

    private final PagePreviewPane weavePreview = new PagePreviewPane();

    private final Button prevPageBtn = new Button("上一页");
    private final Button nextPageBtn = new Button("下一页");
    private final SelectableLabel pageNavLabel = new SelectableLabel("0 / 0");

    private final javafx.scene.control.TreeItem<SlotValue> outputRoot = new javafx.scene.control.TreeItem<>();
    private final javafx.scene.control.TreeTableView<SlotValue> outputTable = new javafx.scene.control.TreeTableView<>(outputRoot);
    private final PageKindPickerBar pickerBar = new PageKindPickerBar();
    private final SlotTablePane slotPane = new SlotTablePane(outputTable, this::refreshOutputFromFamily);
    private javafx.scene.layout.Region rightPanel;
    private VBox ruleBox;
    private javafx.scene.layout.VBox layoutListBox;
    private javafx.scene.control.Label layoutListTitle;
    private javafx.scene.control.Separator layoutSep;
    private byte[] pagePngForLayout;
    // 按页缓存输出树状态（翻页不丢数据）
    private final java.util.Map<Integer, java.util.List<javafx.scene.control.TreeItem<SlotValue>>> outputCache = new java.util.HashMap<>();
    // 手动配对：点击选中行，K=标签 V=值
    private com.weavelay.core.model.OcrRow selectedKeyRow;
    private com.weavelay.core.model.OcrRow lastClickedRow;

    private WeaveLayRuntime runtime;
    private Stage primaryStage;

    private final List<WeavePageResult> sessionPages = new ArrayList<WeavePageResult>();
    private int currentPageIndex = 0;

    private Button applyBtn;
    private Button confirmButton;
    private Button viewOutputJsonBtn;
    private Button exportExcelBtn;
    private final com.weavelay.core.output.OutputJsonEngine outputJsonEngine =
            new com.weavelay.core.output.OutputJsonEngine();
    private javafx.stage.Stage outputJsonStage;
    private com.weavelay.app.ui.JsonViewerWebPane outputJsonViewer;
    private Button saveRulesBtn;
    private Button prevFileBtn;
    private Button nextFileBtn;
    private Button symbolKeyBtn;
    private javafx.scene.control.TextInputControl lastEditField;
    private ModeSwitch workModeSwitch;
    private Label modeTemplateLabel;
    private Label modeApplyLabel;
    private AppPreferences.WorkMode workMode = AppPreferences.WorkMode.TEMPLATE;
    private final java.util.Map<String, String[]> applyTableHeaders = new java.util.HashMap<>();
    /** 按页缓存「应用」后的表格弹窗数据；换 PDF 时整体清空。 */
    private final java.util.Map<Integer, java.util.Map<String, ApplyTableCache>> applyTableCacheByPage =
            new java.util.HashMap<>();
    /** 应用模式框选重识的会话区域覆盖（不写库）；换 PDF 时清空。 */
    private final java.util.Map<Integer, java.util.Map<String, RectRegion>> applyRegionOverridesByPage =
            new java.util.HashMap<>();
    private javafx.scene.control.ComboBox<com.weavelay.core.store.PageKindRecord> pageKindCombo;
    private javafx.scene.control.Label shortcutHint;
    private Button shortcutToggle;
    private volatile Thread weaveWorker;

    public static void main(String[] args) {
        com.weavelay.ocr.TempDirBootstrap.ensure();
        // 工业模板 styles.xml 压缩比常低于 POI 默认阈值，避免误报 Zip bomb
        com.weavelay.app.export.TemplateExcelFiller.relaxZipBombLimit();
        // 不要用 prism.order=sw：软件绘制 + DirectWrite 画字会崩（hs_err QuantumRenderer）
        if ("sw".equalsIgnoreCase(System.getProperty("prism.order"))) {
            System.clearProperty("prism.order");
        }
        if (System.getProperty("prism.lcdtext") == null) {
            System.setProperty("prism.lcdtext", "false");
        }
        RapidOcrService.prepareNativeRuntime();
        try {
            WeaveLayRuntime.open();
        } catch (Exception ex) {
            System.err.println("初始化本地规则库失败: " + ex.getMessage());
            ex.printStackTrace();
        }
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // 提前初始化 OCR（V4 或 V5），避免首次调用时卡住
        ocrService.warmupInBackground(status -> Platform.runLater(() -> setStatus(status)));

        try {
            runtime = WeaveLayRuntime.open();
        } catch (Exception ex) {
            setStatus("规则库初始化失败: " + ex.getMessage());
        }

        preferences.load();
        workMode = preferences.getWorkMode();

        // 初始化版式检测服务 (PP-DocLayout ONNX)
        try {
            batchService.setLayoutService(new com.weavelay.ocr.layout.PpDocLayoutOnnxService());
        } catch (Exception ignored) {
        }
        prevFileBtn = new Button("上一文件");
        prevFileBtn.getStyleClass().add("app-btn");
        prevFileBtn.setVisible(false);
        prevFileBtn.setManaged(false);
        prevFileBtn.setOnAction(e -> switchToAdjacentFile(-1));
        nextFileBtn = new Button("下一文件");
        nextFileBtn.getStyleClass().add("app-btn");
        nextFileBtn.setVisible(false);
        nextFileBtn.setManaged(false);
        nextFileBtn.setOnAction(e -> switchToAdjacentFile(+1));
        setupPickerBar();
        refreshOutputFromFamily();

        Button pickFolder = new Button("选择文件夹");
        pickFolder.getStyleClass().add("app-btn");
        pickFolder.setMinWidth(200);
        pickFolder.setPrefWidth(200);
        pickFolder.setMaxWidth(200);
        pickFolder.setOnAction(e -> chooseFolder(stage));

        workModeSwitch = new ModeSwitch();
        workModeSwitch.setSelected(workMode == AppPreferences.WorkMode.APPLY);
        modeTemplateLabel = new Label("模板");
        modeTemplateLabel.getStyleClass().add("mode-switch-label");
        modeApplyLabel = new Label("应用");
        modeApplyLabel.getStyleClass().add("mode-switch-label");
        workModeSwitch.selectedProperty().addListener((obs, was, on) -> {
            AppPreferences.WorkMode next = on
                    ? AppPreferences.WorkMode.APPLY
                    : AppPreferences.WorkMode.TEMPLATE;
            if (next != workMode) {
                workMode = next;
                preferences.setWorkMode(workMode);
                refreshModeUi();
            } else {
                updateModeSwitchLabels();
            }
        });

        Button copyStatus = new Button("复制");
        copyStatus.getStyleClass().add("app-btn");
        copyStatus.setOnAction(e -> copyStatusToClipboard());

        Button settingsBtn = SettingsButtonFactory.create(
                runtime, preferences, ocrService, licenseService, this::onSettingsSaved);

        HBox modeGearBox = new HBox(8, modeTemplateLabel, workModeSwitch, modeApplyLabel, settingsBtn);
        modeGearBox.setAlignment(Pos.CENTER_RIGHT);

        prevPageBtn.setDisable(true);
        nextPageBtn.setDisable(true);
        prevPageBtn.getStyleClass().add("app-btn");
        nextPageBtn.getStyleClass().add("app-btn");
        prevPageBtn.setOnAction(e -> showPage(currentPageIndex - 1));
        nextPageBtn.setOnAction(e -> showPage(currentPageIndex + 1));


        statusLabel.setMaxWidth(560);
        statusLabel.setMinWidth(140);
        statusLabel.setPrefWidth(420);
        statusLabel.setAlignment(Pos.CENTER_RIGHT);
        statusLabel.getStyleClass().add("app-label-muted");

        folderNameLabel.setMaxWidth(Double.MAX_VALUE);
        folderNameLabel.setMinWidth(80);
        folderNameLabel.setAlignment(Pos.CENTER_LEFT);
        folderNameLabel.getStyleClass().add("app-label-meta");
        HBox.setHgrow(folderNameLabel, Priority.ALWAYS);

        fileNameLabel.setMaxWidth(Double.MAX_VALUE);
        fileNameLabel.setMinWidth(120);
        fileNameLabel.setAlignment(Pos.CENTER);
        fileNameLabel.getStyleClass().add("app-label-meta");

        HBox folderLine = new HBox(10, pickFolder, folderNameLabel);
        folderLine.setAlignment(Pos.CENTER_LEFT);

        GridPane headerGrid = new GridPane();
        headerGrid.setHgap(8);
        headerGrid.setVgap(4);
        headerGrid.setPadding(new Insets(6, 12, 6, 12));
        headerGrid.getStyleClass().add("app-header");

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(96);
        labelColumn.setPrefWidth(96);
        ColumnConstraints controlColumn = new ColumnConstraints();
        controlColumn.setMinWidth(200);
        controlColumn.setPrefWidth(200);
        ColumnConstraints midColumn = new ColumnConstraints();
        midColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints metaColumn = new ColumnConstraints();
        metaColumn.setHgrow(Priority.SOMETIMES);
        metaColumn.setMinWidth(220);
        headerGrid.getColumnConstraints().addAll(labelColumn, controlColumn, midColumn, metaColumn);

        Label familyLabel = new Label("文件类型：");
        Label folderLabel = new Label("文件夹：");
        familyLabel.getStyleClass().add("app-label-field");
        folderLabel.getStyleClass().add("app-label-field");
        GridPane.setHalignment(familyLabel, HPos.RIGHT);
        GridPane.setHalignment(folderLabel, HPos.RIGHT);

        HBox rightBox = new HBox(8, prevFileBtn, nextFileBtn, statusLabel, copyStatus);
        rightBox.setAlignment(Pos.CENTER_RIGHT);

        // 上：文件类型；下：文件夹 | 当前文件名(中间) | 上一/下一文件+状态
        headerGrid.add(familyLabel, 0, 0);
        headerGrid.add(pickerBar.getFamilyCombo(), 1, 0);
        headerGrid.add(modeGearBox, 3, 0);
        GridPane.setHalignment(modeGearBox, HPos.RIGHT);
        GridPane.setValignment(modeGearBox, javafx.geometry.VPos.CENTER);

        headerGrid.add(folderLabel, 0, 1);
        headerGrid.add(folderLine, 1, 1);
        headerGrid.add(fileNameLabel, 2, 1);
        GridPane.setHalignment(fileNameLabel, HPos.CENTER);
        GridPane.setHgrow(fileNameLabel, Priority.ALWAYS);
        headerGrid.add(rightBox, 3, 1);

        pageNavLabel.setMinWidth(44);
        pageNavLabel.setAlignment(Pos.CENTER);
        pageNavLabel.getStyleClass().add("app-label-meta");

        pageKindCombo = new javafx.scene.control.ComboBox<>();
        pageKindCombo.setPromptText("页面类型");
        pageKindCombo.setPrefWidth(160);
        pageKindCombo.getStyleClass().add("app-combo");
        pageKindCombo.valueProperty().addListener((obs, old, val) -> {
            if (val != null) {
                applyPageKind(val);
            }
        });

        Label kindLabel = new Label("页面类型:");
        kindLabel.getStyleClass().add("app-label-field");

        shortcutHint = new Label();
        shortcutHint.getStyleClass().add("app-hint");
        shortcutHint.setPadding(new Insets(2, 12, 4, 12));
        shortcutHint.setMaxWidth(Double.MAX_VALUE);
        shortcutHint.setVisible(true);
        shortcutHint.setManaged(true);

        shortcutToggle = new Button("快捷键 ▾");
        shortcutToggle.getStyleClass().add("app-btn");
        shortcutToggle.setOnAction(e -> {
            boolean show = !shortcutHint.isVisible();
            shortcutHint.setVisible(show);
            shortcutHint.setManaged(show);
            shortcutToggle.setText(show ? "快捷键 ▾" : "快捷键 ▸");
            if (show) refreshShortcutHint();
        });


        HBox pageToolbar = new HBox(8,
                prevPageBtn,
                pageNavLabel,
                nextPageBtn);
        pageToolbar.setAlignment(Pos.CENTER_LEFT);
        pageToolbar.setPadding(new Insets(4, 8, 4, 8));
        pageToolbar.getStyleClass().add("app-toolbar");

        // spacer to push toggle to right
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        pageToolbar.getChildren().addAll(toolbarSpacer, shortcutToggle);

        VBox pageToolbarBox = new VBox(pageToolbar, shortcutHint);

        VBox previewColumn = new VBox(pageToolbarBox, weavePreview);
        VBox.setVgrow(weavePreview, Priority.ALWAYS);

        setupOutputTable();
        setupTableHighlight();
        weavePreview.setOcrRowClickHandler(this::handleOcrBoxClick);
        weavePreview.setRegionSelectHandler(this::handleRegionSelect);
        weavePreview.setSlotBindHandler(this::bindRegionToTopLevelSlot);
        weavePreview.setValueChipEditHandler(this::applyValueChipEdit);
        weavePreview.setTableCellEditHandler(this::applyTableCellChipEdit);
        weavePreview.setValueChipDismissHandler(() -> dismissOutputFocus(true));
        weavePreview.setRememberedZoom(preferences.getPreviewZoom());
        weavePreview.setZoomChangeHandler(preferences::setPreviewZoom);
        // 页面类型放左侧缩放栏最右，避免挤右侧顶栏
        weavePreview.setZoomBarTrailing(kindLabel, pageKindCombo);

        // 布局区域列表
        this.layoutListTitle = new javafx.scene.control.Label("规则");
        this.layoutListTitle.setStyle("-fx-font-weight: bold; -fx-padding: 4 0 2 0;");
        this.layoutListTitle.setVisible(false);
        this.layoutListBox = new javafx.scene.layout.VBox(2);
        this.layoutListBox.setVisible(false);
        this.layoutListBox.setStyle("-fx-padding: 0 0 4 0;");
        javafx.scene.control.ScrollPane layoutScroll = new javafx.scene.control.ScrollPane(layoutListBox);
        layoutScroll.setFitToWidth(true);
        layoutScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        layoutScroll.setMaxHeight(300);
        this.layoutSep = new javafx.scene.control.Separator();
        this.layoutSep.setVisible(false);

        // 输出面板标题栏：应用=按已存区域裁图填充（页型选中时已加载字段骨架）
        applyBtn = new Button("应用");
        applyBtn.getStyleClass().add("app-btn");
        applyBtn.setDisable(true);
        applyBtn.setMinWidth(Region.USE_PREF_SIZE);
        applyBtn.setOnAction(e -> applyCurrentPage());
        saveRulesBtn = new Button("保存");
        saveRulesBtn.getStyleClass().add("app-btn");
        saveRulesBtn.setMinWidth(Region.USE_PREF_SIZE);
        saveRulesBtn.setOnAction(e -> saveCurrentRules());
        confirmButton = new Button("确认");
        confirmButton.getStyleClass().add("app-btn");
        confirmButton.setMinWidth(Region.USE_PREF_SIZE);
        confirmButton.setOnAction(e -> confirmCurrentPage());
        viewOutputJsonBtn = new Button("当前JSON");
        viewOutputJsonBtn.getStyleClass().addAll("app-btn", "app-btn-json");
        viewOutputJsonBtn.setMinWidth(Region.USE_PREF_SIZE);
        viewOutputJsonBtn.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        viewOutputJsonBtn.setOnAction(e -> showOutputJsonViewer());
        exportExcelBtn = new Button("写出Excel");
        exportExcelBtn.getStyleClass().add("app-btn");
        exportExcelBtn.setMinWidth(Region.USE_PREF_SIZE);
        exportExcelBtn.setOnAction(e -> exportAccumulatedJsonToExcel());
        applyElapsedLabel.setPrefWidth(90);
        applyElapsedLabel.setMinWidth(72);
        applyElapsedLabel.setMaxWidth(110);
        applyElapsedLabel.setAlignment(Pos.CENTER_RIGHT);
        applyElapsedLabel.getStyleClass().add("app-label-meta");
        symbolKeyBtn = AppIcons.iconButton(AppIcons.Kind.KEYBOARD,
                "特殊键盘  " + IndustrialSymbolPicker.hotkeyLabel(), true);
        symbolKeyBtn.setFocusTraversable(false);
        symbolKeyBtn.setOnAction(e -> openSymbolPickerForFocus());
        javafx.scene.layout.HBox btnGroup = new javafx.scene.layout.HBox(6,
                applyElapsedLabel, symbolKeyBtn, applyBtn, saveRulesBtn, confirmButton,
                exportExcelBtn);
        btnGroup.setAlignment(Pos.CENTER_RIGHT);
        // 右侧按钮优先完整显示（写出Excel 等），勿被挤出可视区
        btnGroup.setMinWidth(0);
        btnGroup.setPrefWidth(Region.USE_COMPUTED_SIZE);
        btnGroup.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        spacer.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(btnGroup, Priority.NEVER);
        javafx.scene.layout.HBox outHeader = new javafx.scene.layout.HBox(4, spacer, btnGroup);
        outHeader.setAlignment(Pos.CENTER_LEFT);
        outHeader.setPadding(new Insets(0, 0, 2, 0));
        outHeader.setMinHeight(Region.USE_PREF_SIZE);
        outHeader.setMinWidth(0);

        // 右侧 StackPane：输出表在下；当前JSON 右下角；规则条底栏悬浮
        VBox topBox = new VBox(outHeader, slotPane);
        topBox.setMinWidth(0);
        topBox.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(slotPane, Priority.ALWAYS);
        layoutScroll.setMaxHeight(230);
        layoutScroll.setMinHeight(100);
        layoutListTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 2 8 0 8;");
        ruleBox = new VBox(layoutSep, layoutListTitle, layoutScroll);
        ruleBox.setStyle("-fx-background-color: #E8F5E9; -fx-border-color: #4CAF50; -fx-border-width: 2 0 0 0;"
                + " -fx-background-radius: 6 6 0 0;");
        ruleBox.setMinHeight(250);
        ruleBox.setMaxHeight(250);
        ruleBox.setVisible(false);
        ruleBox.setManaged(false);
        StackPane.setAlignment(viewOutputJsonBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(viewOutputJsonBtn, new Insets(0, 10, 10, 0));
        StackPane rightStack = new StackPane(topBox, viewOutputJsonBtn, ruleBox);
        StackPane.setAlignment(ruleBox, Pos.BOTTOM_CENTER);
        rightPanel = rightStack;
        setupOutputClickBinding();
        // 左右按 SplitPane 比例分配；min=0 以免子控件 minWidth 顶破 8:2
        rightPanel.setMinWidth(0);
        rightPanel.setPrefWidth(Region.USE_COMPUTED_SIZE);
        rightPanel.setMaxWidth(Double.MAX_VALUE);
        topBox.setMaxWidth(Double.MAX_VALUE);
        previewColumn.setMinWidth(0);
        previewColumn.setMaxWidth(Double.MAX_VALUE);
        rightPanel.setStyle("-fx-background-color: rgba(255,255,255,0.92);");
        outputTable.setStyle("-fx-background-color: transparent;");
        slotPane.setStyle("-fx-background-color: transparent;");

        // SplitPane: 左侧 PDF 预览 + 右侧面板按比例（8:2），可拖拽
        javafx.scene.control.SplitPane mainSplit = new javafx.scene.control.SplitPane();
        mainSplit.getItems().addAll(previewColumn, rightPanel);
        mainSplit.setDividerPositions(MAIN_SPLIT_RATIO);
        mainSplit.setMaxWidth(Double.MAX_VALUE);
        mainSplit.getStyleClass().add("app-split-pane");
        mainSplit.setStyle("-fx-background-color: transparent;");
        // 全屏/拉宽时 SplitPane 常按像素锁分割条；宽度变化后强制写回比例
        mainSplit.widthProperty().addListener((obs, oldW, newW) -> {
            if (newW == null || newW.doubleValue() <= 0) {
                return;
            }
            applyMainSplitRatio(mainSplit);
            Platform.runLater(() -> applyMainSplitRatio(mainSplit));
        });

        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        VBox root = new VBox(headerGrid, mainSplit);
        root.getStyleClass().add("app-root");
        root.setMaxWidth(Double.MAX_VALUE);
        headerGrid.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        stage.setTitle("织版 WeaveLay2");
        Scene scene = new Scene(root, 1400, 820);
        scene.getStylesheets().addAll(
                getClass().getResource("ui/settings.css").toExternalForm(),
                getClass().getResource("ui/app.css").toExternalForm());
        stage.setScene(scene);
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (handlePageShortcut(e)) {
                e.consume();
                return;
            }
            handleKeyPress(e);
        });
        stage.maximizedProperty().addListener((obs, was, now) ->
                Platform.runLater(() -> applyMainSplitRatio(mainSplit)));
        stage.widthProperty().addListener((obs, oldW, newW) ->
                Platform.runLater(() -> applyMainSplitRatio(mainSplit)));
        stage.show();
        Platform.runLater(() -> applyMainSplitRatio(mainSplit));

        // 布局区域点击回调
        
        weavePreview.setRowRangeHandler(this::handleRowRangeSelect);

        if (applyBtn != null) applyBtn.setDisable(false);
        refreshModeUi();
        restoreLastInput();
        LicenseStatus lic = licenseService.refresh();
        if (!lic.isUsable()) {
            setStatus("未授权 · 请到 设置 → Licence 复制机器指纹 / 导入授权文件");
        }
    }

    /** 强制主分栏保持 8:2，全屏/拉宽后不按像素漂移。 */
    private static void applyMainSplitRatio(javafx.scene.control.SplitPane split) {
        if (split == null || split.getWidth() <= 0) {
            return;
        }
        split.setDividerPositions(MAIN_SPLIT_RATIO);
    }

    /** 按模板/应用模式显隐按钮。 */
    private void refreshModeUi() {
        boolean apply = workMode == AppPreferences.WorkMode.APPLY;
        if (workModeSwitch != null && workModeSwitch.isSelected() != apply) {
            workModeSwitch.setSelected(apply);
        }
        updateModeSwitchLabels();
        // 应用：区域填充 + 确认写出；模板：保存规则
        if (applyBtn != null) {
            applyBtn.setVisible(apply);
            applyBtn.setManaged(apply);
        }
        if (symbolKeyBtn != null) {
            symbolKeyBtn.setVisible(apply);
            symbolKeyBtn.setManaged(apply);
        }
        if (confirmButton != null) {
            confirmButton.setVisible(apply);
            confirmButton.setManaged(apply);
        }
        if (viewOutputJsonBtn != null) {
            viewOutputJsonBtn.setVisible(apply);
            viewOutputJsonBtn.setManaged(apply);
        }
        if (exportExcelBtn != null) {
            exportExcelBtn.setVisible(apply);
            exportExcelBtn.setManaged(apply);
        }
        if (saveRulesBtn != null) {
            saveRulesBtn.setVisible(!apply);
            saveRulesBtn.setManaged(!apply);
        }
        // 应用/模板均可框选；应用模式圈选后点字段重识（不写库）
        weavePreview.setRegionSelectEnabled(true);
        if (apply) {
            showRuleArea(false);
            pendingRegion = null;
            selectedKeyRow = null;
            lastClickedRow = null;
            pendingRowStart = 0;
            pendingRowEnd = 0;
            pendingRowGroup = null;
            pendingDigits = "";
            weavePreview.clearPendingSelection();
            weavePreview.setSlotLabels(null);
        } else {
            // 模板模式：旁白只干扰圈框绑定，一律关掉
            weavePreview.clearValueChip();
            weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
            activeTableSlotCode = null;
            pendingRegion = null;
            weavePreview.clearPendingSelection();
            weavePreview.setSlotLabels(null);
        }
        syncApplyTableColumnChildren();
        if (outputTable != null) {
            outputTable.refresh();
        }
        if (apply) {
            activateApplyTableVirtualCells();
        }
        setStatus(apply
                ? "应用模式：选页型→应用→点绿格对照→确认 JSON→写出Excel"
                : "模板模式：圈框绑定→保存规则");
    }

    /**
     * 应用模式：表格只保留父行（列头子节点多余且值为空）；
     * 模板模式：恢复列子节点便于绑列。
     */
    private void syncApplyTableColumnChildren() {
        boolean apply = workMode == AppPreferences.WorkMode.APPLY;
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) {
                continue;
            }
            String code = ti.getValue().getSlotCode();
            if (code == null || !applyTableHeaders.containsKey(code)) {
                continue;
            }
            if (apply) {
                ti.getChildren().clear();
                ti.setExpanded(false);
            } else if (ti.getChildren().isEmpty()) {
                String[] cols = applyTableHeaders.get(code);
                if (cols == null) {
                    continue;
                }
                for (int ci = 0; ci < cols.length; ci++) {
                    String name = cols[ci] == null ? "" : cols[ci].trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    ti.getChildren().add(new javafx.scene.control.TreeItem<>(
                            new SlotValue(code + "_c" + ci, name, "", null, null)));
                }
                ti.setExpanded(true);
            }
        }
        if (currentPageIndex >= 0) {
            outputCache.put(currentPageIndex, new java.util.ArrayList<>(outputRoot.getChildren()));
        }
    }

    private void updateModeSwitchLabels() {
        boolean apply = workMode == AppPreferences.WorkMode.APPLY;
        if (modeTemplateLabel != null) {
            modeTemplateLabel.getStyleClass().remove("active");
            if (!apply) {
                modeTemplateLabel.getStyleClass().add("active");
            }
        }
        if (modeApplyLabel != null) {
            modeApplyLabel.getStyleClass().remove("active");
            if (apply) {
                modeApplyLabel.getStyleClass().add("active");
            }
        }
    }

    private void setupOutputTable() {
        javafx.scene.control.TreeTableColumn<SlotValue, String> labelCol =
                new javafx.scene.control.TreeTableColumn<>("字段");
        labelCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getValue().getSlotLabel()));
        labelCol.setPrefWidth(140);
        labelCol.setMinWidth(96);
        labelCol.setMaxWidth(220);
        labelCol.setCellFactory(col -> new javafx.scene.control.TreeTableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                }
            }
        });

        javafx.scene.control.TreeTableColumn<SlotValue, String> valueCol =
                new javafx.scene.control.TreeTableColumn<>("值");
        valueCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getValue().getValue()));
        valueCol.setPrefWidth(260);
        valueCol.setMinWidth(120);
        valueCol.setMaxWidth(Double.MAX_VALUE);
        valueCol.setEditable(true);
        valueCol.setCellFactory(col -> new javafx.scene.control.TreeTableCell<>() {
            private final TextField editor = new TextField();

            {
                editor.setStyle(
                        "-fx-background-color: transparent; -fx-border-color: transparent; "
                        + "-fx-font-family: 'Consolas', 'Microsoft YaHei', monospace; -fx-font-size: 11px;");
                editor.focusedProperty().addListener((obs, was, now) -> {
                    if (now) {
                        lastEditField = editor;
                    } else {
                        commitEdit(editor.getText());
                    }
                });
                editor.setOnAction(e -> commitEdit(editor.getText()));
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean canEdit = workMode == AppPreferences.WorkMode.APPLY;
                javafx.scene.control.TreeItem<SlotValue> ti =
                        getTableRow() == null ? null : getTableRow().getTreeItem();
                boolean tableParent = ti != null && isTableParentItem(ti);
                String raw = item == null ? "" : item;
                if (tableParent && canEdit) {
                    // 单行 TextField 会把 markdown 换行挤成一团；表格用摘要，点字段名打开弹窗
                    setGraphic(null);
                    setText(summarizeTableValueForList(raw));
                    editor.setEditable(false);
                } else if (canEdit) {
                    editor.setText(raw);
                    editor.setEditable(true);
                    setGraphic(editor);
                    setText(null);
                } else {
                    SelectableLabel tf = new SelectableLabel(
                            tableParent ? summarizeTableValueForList(raw) : raw,
                            "-fx-font-family: 'Consolas', 'Microsoft YaHei', monospace; -fx-font-size: 11px;");
                    setGraphic(tf);
                    setText(null);
                }
            }

            @Override
            public void commitEdit(String newValue) {
                javafx.scene.control.TreeItem<SlotValue> ti = getTableRow() == null
                        ? null : getTableRow().getTreeItem();
                if (ti == null || ti.getValue() == null) {
                    return;
                }
                SlotValue old = ti.getValue();
                // 表格父行不在「值」列直改（避免单行框毁掉 markdown）
                if (isTableParentItem(ti)) {
                    return;
                }
                String nv = newValue == null ? "" : newValue;
                String oldVal = old.getValue() == null ? "" : old.getValue();
                if (nv.equals(oldVal)) {
                    return;
                }
                // 失焦/单元格复用时 TextField 常变成空串，不能把已有旁白值冲掉
                if (nv.isBlank() && !oldVal.isBlank()) {
                    return;
                }
                ti.setValue(new SlotValue(
                        old.getSlotCode(), old.getSlotLabel(), nv,
                        old.getLabelRow(), old.getValueRow()));
            }
        });

        outputTable.getColumns().add(labelCol);
        outputTable.getColumns().add(valueCol);
        outputTable.setEditable(true);
        outputTable.setColumnResizePolicy(javafx.scene.control.TreeTableView.CONSTRAINED_RESIZE_POLICY);
        outputTable.setPlaceholder(new SelectableLabel("点击右上角齿轮 → 设置输出字段"));
        outputTable.getStyleClass().add("app-slot-table");
        outputTable.setMinHeight(200);
        outputTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        outputTable.setShowRoot(false);
    }

    private void setupOutputClickBinding() {
        if (rightPanel == null) return;
        rightPanel.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (pendingRegion == null) return;
            javafx.scene.Node node = (javafx.scene.Node) event.getTarget();
            while (node != null && node != outputTable) {
                // TreeTableView 的行是 TreeTableRow, 可以直接拿到 TreeItem
                if (node instanceof javafx.scene.control.TreeTableRow) {
                    @SuppressWarnings("unchecked")
                    javafx.scene.control.TreeTableRow<SlotValue> treeRow =
                            (javafx.scene.control.TreeTableRow<SlotValue>) node;
                    javafx.scene.control.TreeItem<SlotValue> item = treeRow.getTreeItem();
                    if (item != null && item.getValue() != null) {
                        if (workMode == AppPreferences.WorkMode.APPLY) {
                            reRecognizeFromPendingRegionForItem(item);
                            event.consume();
                        } else {
                            int idx = SlotTableHelper.flatIndexOf(outputRoot, item.getValue());
                            if (idx >= 0 && idx < SlotTableHelper.flatOutputItems(outputRoot).size()) {
                                bindRegionToSlot(idx);
                                event.consume();
                            }
                        }
                    }
                    return;
                }
                if (node instanceof javafx.scene.control.TableRow) {
                    @SuppressWarnings("unchecked")
                    javafx.scene.control.TableRow<SlotValue> row =
                            (javafx.scene.control.TableRow<SlotValue>) node;
                    if (!row.isEmpty()) {
                        int idx = row.getIndex();
                        if (idx >= 0 && idx < SlotTableHelper.flatOutputItems(outputRoot).size()) {
                            if (workMode == AppPreferences.WorkMode.APPLY) {
                                javafx.scene.control.TreeItem<SlotValue> ti =
                                        SlotTableHelper.findTreeItemAtFlatIndex(outputRoot, idx);
                                if (ti != null) {
                                    reRecognizeFromPendingRegionForItem(ti);
                                }
                            } else {
                                bindRegionToSlot(idx);
                            }
                            event.consume();
                        }
                    }
                    return;
                }
                node = node.getParent();
            }
        });
    }

    private void setupTableHighlight() {
        outputTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                applyOutputHighlight(null);
                if (workMode == AppPreferences.WorkMode.APPLY) {
                    activateApplyTableVirtualCells();
                } else {
                    activeTableSlotCode = null;
                    weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
                }
                showRuleArea(false);
                return;
            }
            applyOutputHighlight(selected.getValue());
            if (workMode == AppPreferences.WorkMode.APPLY) {
                showRuleArea(false);
            } else {
                showRuleForSlot(selected.getValue());
            }
        });
        // 右键：取消字段选中 / 关掉旁白
        outputTable.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                dismissOutputFocus(true);
                e.consume();
                return;
            }
            if (workMode != AppPreferences.WorkMode.APPLY) {
                return;
            }
            // 应用模式：单击表格父节点 → 用「应用」缓存弹窗（不自动弹）
            // 框选重识进行中（应用按钮禁用）时不弹，避免与重识抢点击
            if (applyBtn != null && applyBtn.isDisable()) {
                return;
            }
            javafx.scene.control.TreeItem<SlotValue> selected =
                    outputTable.getSelectionModel().getSelectedItem();
            if (isTableParentItem(selected)) {
                openCachedTablePopup(selected);
            }
        });
    }

    /**
     * 取消右侧输出字段选中，并关掉预览旁白/高亮。
     * @return 是否确实清掉了选中或旁白
     */
    private boolean dismissOutputFocus(boolean announce) {
        boolean hadSelection = outputTable.getSelectionModel().getSelectedItem() != null;
        boolean hadChip = weavePreview != null && weavePreview.getValueChipSlotCode() != null;
        if (hadSelection) {
            outputTable.getSelectionModel().clearSelection();
        } else if (hadChip) {
            weavePreview.clearHighlight();
            weavePreview.clearValueChip();
            if (workMode == AppPreferences.WorkMode.APPLY) {
                activateApplyTableVirtualCells();
            } else {
                activeTableSlotCode = null;
                weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
            }
            showRuleArea(false);
        }
        boolean cleared = hadSelection || hadChip;
        if (announce && cleared) {
            setStatus("已取消字段选中");
        }
        return cleared;
    }

    private record ApplyTableCache(
            String slotDefLabel,
            List<com.weavelay.core.layout.CellRect> cells,
            List<OcrRow> ocrRows,
            int regionX,
            int regionY,
            int regionW,
            int regionH,
            /** 弹窗数据行 0 → 物理 cell.row = index + offset（顶边距列时多为 1） */
            int dataRowPhysOffset,
            /** 弹窗数据列 0 → 物理 cell.col = index + offset（左边距列时多为 1） */
            int dataColPhysOffset) {}

    /**
     * 与 {@link TablePopupManager#buildTableMarkdown} 相同的顶/左边距判定：
     * 仅当空边框在外、有内容的格更靠内时才偏移，避免 (0,0) 左上角 OCR 被减掉。
     */
    private static int[] tableDataPhysOffsets(
            List<com.weavelay.core.layout.CellRect> cells, String slotDefLabel) {
        if (cells == null || cells.isEmpty()) {
            return new int[] {0, 0};
        }
        java.util.Set<String> headerSet = new java.util.HashSet<>();
        TableLabelParser.ParsedTable pt = TableLabelParser.parse(slotDefLabel);
        if (pt.columns() != null) {
            for (String h : pt.columns()) {
                if (h != null && !h.isBlank()) {
                    headerSet.add(h.trim());
                }
            }
        }
        java.util.List<com.weavelay.core.layout.CellRect> dataCells = new java.util.ArrayList<>();
        for (com.weavelay.core.layout.CellRect cell : cells) {
            if (cell == null) {
                continue;
            }
            String t = cell.getText() != null ? cell.getText().trim() : "";
            if (cell.getRowspan() == 1 && !t.isEmpty() && headerSet.contains(t)) {
                continue;
            }
            dataCells.add(cell);
        }
        if (dataCells.isEmpty()) {
            dataCells = cells;
        }
        int[] margin = TablePopupManager.resolveMarginOffsets(dataCells, null);
        TableLabelParser.ParsedTable parsed = TableLabelParser.parse(slotDefLabel);
        int headerCount = parsed.columns() != null ? parsed.columns().length : 0;
        return TablePopupManager.refineMarginOffsets(dataCells, margin, headerCount);
    }

    /** 是否输出树中的表格父节点（非列子节点）。 */
    private boolean isTableParentItem(javafx.scene.control.TreeItem<SlotValue> item) {
        if (item == null || item.getValue() == null) {
            return false;
        }
        if (item.getParent() != null && item.getParent() != outputRoot) {
            return false; // 子列不算
        }
        String code = item.getValue().getSlotCode();
        if (code != null && applyTableHeaders.containsKey(code)) {
            return true;
        }
        return !item.getChildren().isEmpty();
    }

    private void openCachedTablePopup(javafx.scene.control.TreeItem<SlotValue> tableItem) {
        if (tableItem == null || tableItem.getValue() == null) {
            return;
        }
        SlotValue sv = tableItem.getValue();
        String code = sv.getSlotCode();
        ApplyTableCache cached = null;
        java.util.Map<String, ApplyTableCache> pageCache = applyTableCacheByPage.get(currentPageIndex);
        if (code != null && pageCache != null) {
            cached = pageCache.get(code);
        }

        String title = sv.getSlotLabel() != null ? sv.getSlotLabel() : "表格";
        String[] headers = code != null ? applyTableHeaders.get(code) : null;
        if ((headers == null || headers.length == 0) && tableItem.getChildren() != null) {
            java.util.List<String> cols = new java.util.ArrayList<>();
            for (javafx.scene.control.TreeItem<SlotValue> ch : tableItem.getChildren()) {
                if (ch != null && ch.getValue() != null && ch.getValue().getSlotLabel() != null) {
                    cols.add(ch.getValue().getSlotLabel().trim());
                }
            }
            if (!cols.isEmpty()) {
                headers = cols.toArray(new String[0]);
            }
        }

        // 弹窗以物理格+OCR 重建结构（含空行），再叠用户已改内容。
        // 槽位 Markdown 若整列错位，以 OCR 结构为准。
        String structMd = null;
        if (cached != null) {
            structMd = TablePopupManager.buildTableMarkdown(
                    cached.slotDefLabel(), cached.cells(), cached.ocrRows());
        }
        String slotMdPlain = null;
        if (sv.getValue() != null && !sv.getValue().isBlank()) {
            slotMdPlain = com.weavelay.core.formula.LatexToPlainText.convert(sv.getValue());
        }
        String editMd;
        boolean hasOcrCache = cached != null
                && cached.ocrRows() != null && !cached.ocrRows().isEmpty();
        if (structMd != null && !structMd.isBlank()) {
            if (hasOcrCache && TablePopupManager.seemsColumnShifted(structMd, slotMdPlain)) {
                editMd = structMd;
            } else {
                editMd = TablePopupManager.mergeMarkdownPreserveStructure(structMd, slotMdPlain);
            }
        } else if (slotMdPlain != null && !slotMdPlain.isBlank()) {
            editMd = slotMdPlain;
        } else {
            setStatus("请先点「应用」填充表格，再点击编辑");
            return;
        }
        if (headers == null && cached != null && cached.slotDefLabel() != null) {
            TableLabelParser.ParsedTable pt = TableLabelParser.parse(cached.slotDefLabel());
            if (pt.columns() != null && pt.columns().length > 0) {
                headers = pt.columns();
            }
            if (pt.tableName() != null && !pt.tableName().isBlank()) {
                title = pt.tableName();
            }
        }

        final String slotCode = code;
        final String tableTitle = title;
        javafx.scene.image.Image regionPreview = null;
        WeavePageResult page = currentPageOrNull();
        if (cached != null && page != null && page.getPagePng() != null
                && cached.regionW() > 0 && cached.regionH() > 0) {
            try {
                byte[] crop = OcrUtils.cropPagePng(
                        page.getPagePng(),
                        cached.regionX(), cached.regionY(),
                        cached.regionW(), cached.regionH());
                if (crop != null && crop.length > 0) {
                    regionPreview = new javafx.scene.image.Image(
                            new java.io.ByteArrayInputStream(crop));
                }
            } catch (Exception ignored) {
            }
        }
        java.util.List<TablePopupManager.CropCell> cropCells = cached == null
                ? java.util.List.of()
                : TablePopupManager.collectOcrHits(
                        cached.slotDefLabel(), cached.cells(), cached.ocrRows());
        TablePopupManager.showEditor(tableTitle, headers, editMd, regionPreview, cropCells, savedMd -> {
            if (slotCode == null) {
                setStatus("表格无字段编码，未能写回输出树");
                return;
            }
            fillSlotValue(slotCode, savedMd);
            if (currentPageIndex >= 0) {
                outputCache.put(currentPageIndex, new java.util.ArrayList<>(outputRoot.getChildren()));
            }
            if (outputTable != null) {
                outputTable.refresh();
            }
            // 弹窗改完后立刻刷新绿框/旁白，避免仍显示 OCR 旧值误以为没写进内存
            ApplyTableCache cacheAfter = tableCacheForCode(slotCode);
            if (cacheAfter != null && slotCode.equals(activeTableSlotCode)) {
                weavePreview.setVirtualCellRows(buildTableVirtualCellRows(slotCode, cacheAfter));
            }
            setStatus("已修改表格: " + tableTitle + " — 确认写出即这份内容");
        }, null, primaryStage);
    }

    /** 当前输出树选中的表格槽位（PDF 点格旁白/写回用）。 */
    private String activeTableSlotCode;

    /** 在 PDF 上铺可点的物理格（绿框），点击后旁白对照。 */
    private java.util.List<OcrRow> buildTableVirtualCellRows(String slotCode, ApplyTableCache cache) {
        if (cache == null || cache.cells() == null || cache.cells().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int rx = cache.regionX();
        int ry = cache.regionY();
        int rowOff = cache.dataRowPhysOffset();
        int colOff = cache.dataColPhysOffset();
        java.util.List<OcrRow> rows = new java.util.ArrayList<>();
        for (com.weavelay.core.layout.CellRect cell : cache.cells()) {
            if (cell == null) {
                continue;
            }
            int physRow = cell.getRow();
            int physCol = cell.getCol();
            if (physRow < rowOff || physCol < colOff) {
                continue;
            }
            int dataCol = physCol - colOff;
            int dataRow = physRow - rowOff;
            String ocrText = ocrTextForPhysicalCell(cache.ocrRows(), cell);
            String cellText = cell.getText() != null ? cell.getText().trim() : "";
            // 输出树 markdown 里已有该格（含刻意清空）优先，否则 OCR 会盖住双击纠正
            String display;
            if (tableCellPresentInOutput(slotCode, dataRow, dataCol)) {
                display = tableCellValueFromOutput(slotCode, dataRow, dataCol);
            } else {
                display = !ocrText.isEmpty() ? ocrText : cellText;
            }
            rows.add(new OcrRow(
                    "table-virtual-cell",
                    display,
                    0.0,
                    cell.getX() + rx,
                    cell.getY() + ry,
                    cell.getEndX() + rx,
                    cell.getEndY() + ry,
                    // row, col, slotCode — 多表同时铺绿格时点格能对上所属表
                    new Object[] {dataRow, dataCol, slotCode}));
        }
        return rows;
    }

    /** 应用模式：本页已缓存的表格全部铺上可点绿格，无需先点右侧表名。 */
    private void activateApplyTableVirtualCells() {
        if (workMode != AppPreferences.WorkMode.APPLY || weavePreview == null) {
            return;
        }
        if (currentPageIndex < 0) {
            activeTableSlotCode = null;
            weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
            return;
        }
        java.util.Map<String, ApplyTableCache> pageCache = applyTableCacheByPage.get(currentPageIndex);
        if (pageCache == null || pageCache.isEmpty()) {
            activeTableSlotCode = null;
            weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
            weavePreview.clearValueChip();
            return;
        }
        java.util.List<OcrRow> all = new java.util.ArrayList<>();
        String firstCode = null;
        for (java.util.Map.Entry<String, ApplyTableCache> e : pageCache.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (firstCode == null) {
                firstCode = e.getKey();
            }
            all.addAll(buildTableVirtualCellRows(e.getKey(), e.getValue()));
        }
        activeTableSlotCode = firstCode;
        weavePreview.setVirtualCellRows(all);
        weavePreview.clearValueChip();
    }

    private String tableCellValueFromOutput(String slotCode, int dataRow, int dataCol) {
        if (slotCode == null || dataRow < 0 || dataCol < 0) {
            return "";
        }
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) {
                continue;
            }
            if (!slotCode.equals(ti.getValue().getSlotCode())) {
                continue;
            }
            java.util.List<java.util.List<String>> rows =
                    com.weavelay.app.export.ConfirmExcelWriter.parseMarkdownTable(ti.getValue().getValue());
            int mdRow = dataRow + 1;
            if (rows.size() <= mdRow) {
                return "";
            }
            java.util.List<String> line = rows.get(mdRow);
            return dataCol < line.size() && line.get(dataCol) != null
                    ? line.get(dataCol).trim() : "";
        }
        return "";
    }

    /** 输出树 markdown 是否已包含该数据格（有行且列宽够），用于区分「未填充」与「用户清空」。 */
    private boolean tableCellPresentInOutput(String slotCode, int dataRow, int dataCol) {
        if (slotCode == null || dataRow < 0 || dataCol < 0) {
            return false;
        }
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) {
                continue;
            }
            if (!slotCode.equals(ti.getValue().getSlotCode())) {
                continue;
            }
            String md = ti.getValue().getValue();
            if (md == null || md.isBlank()) {
                return false;
            }
            java.util.List<java.util.List<String>> rows =
                    com.weavelay.app.export.ConfirmExcelWriter.parseMarkdownTable(md);
            int mdRow = dataRow + 1;
            if (rows.size() <= mdRow) {
                return false;
            }
            return dataCol < rows.get(mdRow).size();
        }
        return false;
    }

    private ApplyTableCache tableCacheForCode(String slotCode) {
        if (slotCode == null || slotCode.isBlank() || currentPageIndex < 0) {
            return null;
        }
        java.util.Map<String, ApplyTableCache> pageCache = applyTableCacheByPage.get(currentPageIndex);
        return pageCache == null ? null : pageCache.get(slotCode);
    }

    private void showTableCellChip(OcrRow anchor) {
        if (workMode != AppPreferences.WorkMode.APPLY) {
            weavePreview.clearValueChip();
            return;
        }
        if (anchor == null || !anchor.hasBbox()) {
            return;
        }
        int dataRow = -1;
        int dataCol = -1;
        String slotCode = activeTableSlotCode;
        Object metas = anchor.getMetas();
        if (metas instanceof Object[] pack && pack.length >= 2) {
            if (pack[0] instanceof Number n0) {
                dataRow = n0.intValue();
            }
            if (pack[1] instanceof Number n1) {
                dataCol = n1.intValue();
            }
            if (pack.length >= 3 && pack[2] instanceof String sc && !sc.isBlank()) {
                slotCode = sc;
            }
        } else if (metas instanceof int[] pos && pos.length >= 2) {
            dataRow = pos[0];
            dataCol = pos[1];
        }
        if (slotCode == null || slotCode.isBlank()) {
            return;
        }
        activeTableSlotCode = slotCode;
        // 旁白与绿框同一格；输出树已有该格则不再回退 OCR（否则双击改完再点仍是旧字）
        String text = "";
        boolean fromOutput = false;
        if (dataRow >= 0 && dataCol >= 0
                && tableCellPresentInOutput(slotCode, dataRow, dataCol)) {
            text = tableCellValueFromOutput(slotCode, dataRow, dataCol);
            fromOutput = true;
        }
        ApplyTableCache cache = tableCacheForCode(slotCode);
        if (!fromOutput && cache != null && cache.cells() != null && dataRow >= 0 && dataCol >= 0) {
            int rowOff = cache.dataRowPhysOffset();
            int colOff = cache.dataColPhysOffset();
            for (com.weavelay.core.layout.CellRect cell : cache.cells()) {
                if (cell == null) {
                    continue;
                }
                if (cell.getRow() - rowOff == dataRow && cell.getCol() - colOff == dataCol) {
                    text = ocrTextForPhysicalCell(cache.ocrRows(), cell);
                    if (text.isEmpty() && cell.getText() != null) {
                        text = cell.getText().trim();
                    }
                    break;
                }
            }
        }
        String colHeader = null;
        if (cache != null && cache.slotDefLabel() != null && dataCol >= 0) {
            colHeader = tableColumnHeader(cache.slotDefLabel(), dataCol);
        }
        if (text.isEmpty()) {
            text = tableVirtualCellDisplayText(anchor.getFeature(), colHeader);
        } else {
            text = tableVirtualCellDisplayText(text, colHeader);
        }
        weavePreview.setHighlightRows(java.util.List.of(anchor));
        weavePreview.setTableRowChip(text, anchor, activeTableSlotCode, dataRow, dataCol);
    }

    /**
     * 旧数据可能带「列头\\n内容」；仅当首行像列头时剥掉，避免把「普通车床\\n数控车床」误显示成邻行字。
     */
    private static String tableVirtualCellDisplayText(String feature) {
        return tableVirtualCellDisplayText(feature, null);
    }

    private static String tableVirtualCellDisplayText(String feature, String columnHeader) {
        if (feature == null) {
            return "";
        }
        String t = feature.trim();
        int nl = t.indexOf('\n');
        if (nl < 0 || nl >= t.length() - 1) {
            return t;
        }
        String first = t.substring(0, nl).trim();
        String rest = t.substring(nl + 1).trim();
        if (rest.isEmpty()) {
            return first;
        }
        if (columnHeader != null && !columnHeader.isBlank() && first.equals(columnHeader.trim())) {
            return rest;
        }
        // 多行 OCR：保留全部（旁白可换行），不再默认丢首行
        return t;
    }

    /**
     * 取落在物理格内的 OCR 文案。
     * 只认「文字框中心在格内」，或「重叠面积占 OCR 面积 ≥ 55%」——避免邻行/邻列字因边线重叠漂进本格。
     */
    private static String ocrTextForPhysicalCell(
            List<OcrRow> ocrRows, com.weavelay.core.layout.CellRect cell) {
        if (ocrRows == null || cell == null) {
            return "";
        }
        record Hit(String text, double cy, double cx) {}
        java.util.List<Hit> hits = new java.util.ArrayList<>();
        for (OcrRow ocr : ocrRows) {
            if (ocr == null) {
                continue;
            }
            String text = ocr.getFeature() != null ? ocr.getFeature().trim() : "";
            if (text.isEmpty()) {
                continue;
            }
            double ow = ocr.getEndX() - ocr.getStartX();
            double oh = ocr.getEndY() - ocr.getStartY();
            if (ow <= 0 || oh <= 0) {
                continue;
            }
            double ox = Math.max(ocr.getStartX(), cell.getX());
            double oy = Math.max(ocr.getStartY(), cell.getY());
            double ex = Math.min(ocr.getEndX(), cell.getEndX());
            double ey = Math.min(ocr.getEndY(), cell.getEndY());
            double iw = ex - ox;
            double ih = ey - oy;
            if (iw <= 0 || ih <= 0) {
                continue;
            }
            double cx = (ocr.getStartX() + ocr.getEndX()) / 2.0;
            double cy = (ocr.getStartY() + ocr.getEndY()) / 2.0;
            boolean centerIn = cx >= cell.getX() && cx <= cell.getEndX()
                    && cy >= cell.getY() && cy <= cell.getEndY();
            double cover = (iw * ih) / (ow * oh);
            if (!centerIn && cover < 0.55) {
                continue;
            }
            hits.add(new Hit(text, cy, cx));
        }
        hits.sort(java.util.Comparator.comparingDouble(Hit::cy).thenComparingDouble(Hit::cx));
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (Hit h : hits) {
            uniq.add(h.text());
        }
        return String.join("\n", uniq);
    }

    private static String tableColumnHeader(String slotDefLabel, int dataColIndex) {
        TableLabelParser.ParsedTable pt = TableLabelParser.parse(slotDefLabel);
        String[] cols = pt.columns();
        if (cols != null && dataColIndex >= 0 && dataColIndex < cols.length) {
            return cols[dataColIndex];
        }
        return "列" + (dataColIndex + 1);
    }

    /**
     * 保存当前页所有字段规则到数据库（保留原有坐标和类型）。
     */
    private void saveCurrentRules() {
        if (runtime == null) {
            setStatus("运行环境未初始化");
            return;
        }
        WeavePageResult page = currentPageOrNull();
        if (page == null) {
            setStatus("请先打开PDF并检测页面类型");
            return;
        }
        String kindCode = page.getPageKindCode();
        if (kindCode == null || "unknown".equals(kindCode)) {
            setStatus("页面类型未识别, 无法保存");
            return;
        }
        try {
            List<SlotDefinition> existing = runtime.getDatabase().loadSlots(kindCode);
            List<SlotDefinition> updated = new ArrayList<>(existing);
            for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
                SlotValue sv = ti.getValue();
                String code = sv.getSlotCode();
                if (code == null || code.isEmpty()) continue;
                boolean found = false;
                for (int i = 0; i < updated.size(); i++) {
                    if (updated.get(i).getSlotCode().equals(code)) {
                        SlotDefinition old = updated.get(i);
                        String keepLabel = old.getSlotLabel();
                        // label 只在设置页面修改，保存时原样不动
                        updated.set(i, new SlotDefinition(code, keepLabel, i,
                                old.getRegionX(), old.getRegionY(), old.getRegionW(), old.getRegionH(),
                                old.getFieldType(), old.getFieldMeta()));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // 新建 slot：保持原 label
                    updated.add(new SlotDefinition(code, sv.getSlotLabel(), updated.size()));
                }
            }
            runtime.getDatabase().replaceSlotDefinitions(kindCode, updated);
            runtime.reloadCatalog();
            setStatus("已保存 " + updated.size() + " 条规则到 [" + kindCode + "]");
        } catch (Exception ex) {
            setStatus("保存失败: " + ex.getMessage());
        }
    }

    /**
     * 在右侧面板显示当前选中输出字段的提取规则。
     */
    private void showRuleForSlot(SlotValue slot) {
        if (workMode == AppPreferences.WorkMode.APPLY) {
            showRuleArea(false);
            return;
        }
        layoutListBox.getChildren().clear();
        showRuleArea(true);
        layoutListTitle.setText("规则: " + slot.getSlotLabel());

        String code = slot.getSlotCode();
        String value = slot.getValue();

        addRuleRow("字段名", slot.getSlotLabel());
        addRuleRow("当前值", value.isEmpty() ? "(空)" : value.substring(0, Math.min(60, value.length())));

        // 查数据库坐标（表格子列的 code 如 out_1_c0，查父级 out_1）
        WeavePageResult pg = currentPageOrNull();
        String kindCode = pg != null ? pg.getPageKindCode() : null;
        String lookupCode = code;
        // 子列 code 剥离后缀 _cN
        if (code != null && code.matches(".*_c\\d+$")) {
            lookupCode = code.replaceAll("_c\\d+$", "");
        }
        if (kindCode != null && runtime != null) {
            try {
                for (SlotDefinition sd : runtime.getDatabase().loadSlots(kindCode)) {
                    if (sd.getSlotCode().equals(lookupCode) && sd.hasRegion()) {
                        addRuleRow("提取方式", "像素坐标裁图OCR");
                        addRuleRow("坐标(px)", sd.getRegionX() + "," + sd.getRegionY() + " "
                                + sd.getRegionW() + "x" + sd.getRegionH());
                        javafx.scene.control.Button verifyBtn = new javafx.scene.control.Button("校验坐标");
                        verifyBtn.getStyleClass().add("app-btn");
                        verifyBtn.setOnAction(e -> verifyRegionRule(slot));
                        layoutListBox.getChildren().add(verifyBtn);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (code != null && code.startsWith("@region:")) {
            addRuleRow("提取方式", "布局区域");
            addRuleRow("绑定语法", code);
        } else {
            addRuleRow("提取方式", "未设置");
        }
    }

    /**
     * 校验坐标规则：从 PDF 重新渲染→裁图→OCR→显示结果在校验按钮下方
     */
    private void verifyRegionRule(SlotValue slot) {
        WeavePageResult page = currentPageOrNull();
        if (page == null || selectedPath == null) {
            setStatus("请先打开PDF");
            return;
        }
        String kindCode = page.getPageKindCode();
        if (kindCode == null) return;
        // 子列 code (如 tbl_xxx_c0) 解析为父级 code
        String lookupCode = slot.getSlotCode();
        if (lookupCode != null && lookupCode.matches(".*_c\\d+$")) {
            lookupCode = lookupCode.replaceAll("_c\\d+$", "");
        }
        // 从数据库获取当前字段的坐标（一次查询，复用给 popup）
        SlotDefinition slotDef = null;
        try {
            for (SlotDefinition sd : runtime.getDatabase().loadSlots(kindCode)) {
                if (sd.getSlotCode().equals(lookupCode)) {
                    slotDef = sd;
                    break;
                }
            }
        } catch (Exception ex) {
            setStatus("读规则失败");
            return;
        }
        if (slotDef == null || !slotDef.hasRegion()) {
            setStatus("该字段无坐标规则");
            return;
        }
        final SlotDefinition finalSlotDef = slotDef; // lambda 内需要 effectively final
        // 拼出完整 label：slotLabel 可能只存表名，列头在 fieldMeta.columns JSON 里
        final String slotDefLabel = TableLabelParser.buildFullTableLabel(slotDef);
        final int rx = slotDef.getRegionX(), ry = slotDef.getRegionY();
        final int rw = slotDef.getRegionW(), rh = slotDef.getRegionH();
        final int pageNum = page.getPageNumber();
        final Path pdfPath = selectedPath;
        javafx.scene.control.Label resultLbl = new javafx.scene.control.Label("  校验中...");
        resultLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #7a9494; -fx-padding: 2 0 2 4;");
        layoutListBox.getChildren().add(resultLbl);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                List<byte[]> pngs = PdfPageRenderer.renderToPngBytes(pdfPath);
                if (pageNum < 1 || pageNum > pngs.size()) {
                    Platform.runLater(() -> {
                        layoutListBox.getChildren().remove(resultLbl);
                        setStatus("页码超出范围");
                    });
                    return;
                }
                byte[] png = pngs.get(pageNum - 1);
                // 裁图
                byte[] cropBytes = OcrUtils.cropPagePng(png, rx, ry, rw, rh);
                if (cropBytes == null) {
                    Platform.runLater(() -> {
                        layoutListBox.getChildren().remove(resultLbl);
                        setStatus("裁图失败");
                    });
                    return;
                }
                // OCR — 裁图区域小，继承当前引擎配置即可
                RapidOcrService.prepareNativeRuntime();
                List<OcrRow> ocrRows = ocrService.recognizeImageBytes(cropBytes, "crop");
                final java.nio.file.Path ocrDump = OcrUtils.dumpRawOcrRows(
                        "verify-crop/" + slotDefLabel, rw, rh, ocrRows);
                // 如果是表格字段，跑 TableBorderDetector 检测单元格
                boolean isTableField = false;
                final String tableLookupCode = slot.getSlotCode() != null && slot.getSlotCode().matches(".*_c\\d+$")
                        ? slot.getSlotCode().replaceAll("_c\\d+$", "")
                        : slot.getSlotCode();
                if (kindCode != null && runtime != null) {
                    try {
                        for (SlotDefinition sd : runtime.getDatabase().loadSlots(kindCode)) {
                            if (sd.getSlotCode().equals(tableLookupCode)
                                    && (sd.isTable() || (sd.getSlotLabel() != null && sd.getSlotLabel().startsWith("*")))) {
                                isTableField = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                final boolean showTablePopup = isTableField;
                List<CellRect> tableCells;
                if (!isTableField) {
                    tableCells = Collections.emptyList();
                } else {
                    try {
                        tableCells = com.weavelay.core.layout.TableBorderDetector.detectCells(
                                cropBytes, ocrRows, slotDefLabel);
                    } catch (Exception e) {
                        tableCells = Collections.emptyList();
                    }
                }

                // 整表校验：对 $ 标记列的物理格裁剪再 OCR（打日志）+ 公式识别
                int tableFormulaHits = 0;
                if (isTableField && !tableCells.isEmpty()) {
                    var tableFormula = recognizeFormulaColumnsAfterTablePairing(
                            cropBytes, tableCells, ocrRows, finalSlotDef);
                    tableCells = tableFormula.cells();
                    ocrRows = tableFormula.ocrRows();
                    tableFormulaHits = tableFormula.hitCount();
                }

                String result = OcrUtils.ocrRowsToText(ocrRows);
                final String ocrOriginal = result;

                // 单字段 / 单列坐标校验: $ 标记的字段/列，用裁图调公式 API
                boolean isFormula = finalSlotDef.isFormulaField()
                        || finalSlotDef.isFormulaColumn(slot.getSlotLabel());
                String formulaLatex = null;
                if (!isTableField && isFormula && cropBytes != null) {
                    try {
                        formulaLatex = layoutDetectAndRecognizeFormula(cropBytes, slot.getSlotLabel());
                        if (formulaLatex != null && !formulaLatex.isEmpty()) {
                            result = formulaLatex;
                        }
                    } catch (Exception ignored) {
                    }
                }

                final String displayResult = result;
                final String displayLatex = formulaLatex;
                final List<CellRect> finalTableCells = tableCells;
                final List<OcrRow> finalOcrRows = ocrRows;
                final int finalTableFormulaHits = tableFormulaHits;
                Platform.runLater(() -> {
                    layoutListBox.getChildren().remove(resultLbl);
                    if (displayResult != null && !displayResult.isEmpty()) {
                        if (showTablePopup) {
                            TablePopupManager.show(slotDefLabel, finalTableCells, finalOcrRows, rx, ry);
                        } else {
                            fillSlotValue(slot.getSlotCode(), displayResult);
                        }
                        String preview = displayResult.substring(0, Math.min(60, displayResult.length()));
                        String labelText;
                        if (showTablePopup && finalTableFormulaHits > 0) {
                            labelText = "  OK(公式列 " + finalTableFormulaHits + "): " + preview;
                        } else if (displayLatex != null) {
                            labelText = "  OK(公式): " + preview;
                        } else {
                            labelText = "  OK: " + preview;
                        }
                        javafx.scene.control.Label ok = new javafx.scene.control.Label(labelText);
                        ok.setStyle("-fx-font-size: 11px; -fx-text-fill: #2E7D32; -fx-padding: 2 0 2 4;");
                        layoutListBox.getChildren().add(ok);
                    } else {
                        javafx.scene.control.Label fail = new javafx.scene.control.Label("  FAIL: 坐标区域无文字");
                        fail.setStyle("-fx-font-size: 11px; -fx-text-fill: #C62828; -fx-padding: 2 0 2 4;");
                        layoutListBox.getChildren().add(fail);
                    }
                    setStatus("OCR原文已写入 " + ocrDump.toAbsolutePath());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    layoutListBox.getChildren().remove(resultLbl);
                    javafx.scene.control.Label err = new javafx.scene.control.Label("  ERR: " + ex.getMessage());
                    err.setStyle("-fx-font-size: 11px; -fx-text-fill: #C62828; -fx-padding: 2 0 2 4;");
                    layoutListBox.getChildren().add(err);
                });
            }
        });
    }

    private void showRuleArea(boolean show) {
        if (workMode == AppPreferences.WorkMode.APPLY) {
            show = false;
        }
        layoutListBox.setVisible(show);
        layoutListBox.setManaged(show);
        layoutListTitle.setVisible(show);
        layoutListTitle.setManaged(show);
        layoutSep.setVisible(show);
        layoutSep.setManaged(show);
        if (ruleBox != null) {
            ruleBox.setVisible(show);
            ruleBox.setManaged(show);
        }
    }

    private void addRuleRow(String key, String val) {
        SelectableLabel lbl = new SelectableLabel(
                "  " + key + ": " + (val != null ? val : ""),
                "-fx-font-size: 11px; -fx-padding: 1 0 1 4; -fx-text-fill: #444;");
        layoutListBox.getChildren().add(lbl);
    }

    private void handleOcrBoxClick(OcrRow clicked) {
        WeavePageResult page = currentPageOrNull();
        if (page == null || clicked == null) {
            return;
        }
        if ("table-virtual-cell".equals(clicked.getStreamName())) {
            if (workMode != AppPreferences.WorkMode.APPLY) {
                return;
            }
            showTableCellChip(clicked);
            String hint = tableVirtualCellDisplayText(clicked.getFeature());
            setStatus("表格对照: " + (hint.isEmpty() ? "（空）" : hint));
            return;
        }
        if (workMode == AppPreferences.WorkMode.APPLY) {
            // 应用模式不走 K/V 配对；点普通 OCR 只做高亮
            lastClickedRow = clicked;
            weavePreview.setHighlightRows(List.of(clicked));
            setStatus("已选中: " + clicked.getFeature());
            return;
        }
        lastClickedRow = clicked;
        weavePreview.setHighlightRows(List.of(clicked));
        setStatus("已选中: " + clicked.getFeature() + " — 按K设为标签, 按V设为值, Esc取消");
    }

    private void handleRegionSelect(RectRegion region) {
        if (region == null) {
            this.pendingRegion = null;
            weavePreview.setSlotLabels(null);
            if (workMode == AppPreferences.WorkMode.APPLY) {
                dismissOutputFocus(true);
            } else {
                dismissOutputFocus(false);
            }
            setStatus("已取消框选");
            return;
        }
        this.pendingRegion = region;
        refreshSlotLabelsForPreview();
        if (workMode == AppPreferences.WorkMode.APPLY) {
            setStatus(String.format("已框选 (%.0f,%.0f) %.0f×%.0f — 点击编号或右侧字段重识，右键取消",
                    region.getX(), region.getY(), region.getWidth(), region.getHeight()));
        } else {
            setStatus(String.format("已框选区域 (%.0f,%.0f) %.0f×%.0f — 点击编号绑定, 右键取消",
                    region.getX(), region.getY(), region.getWidth(), region.getHeight()));
        }
    }

    private void handleRowRangeSelect(int startRow, int endRow) {
        if (workMode == AppPreferences.WorkMode.APPLY) {
            return;
        }
        WeavePageResult page = currentPageOrNull();
        if (page == null) return;
        List<com.weavelay.core.model.OcrRow> all = page.getOcrRows();
        if (all.isEmpty()) return;

        // 收集范围行
        List<com.weavelay.core.model.OcrRow> group = new java.util.ArrayList<>();
        for (int i = startRow - 1; i < endRow && i < all.size(); i++) {
            group.add(all.get(i));
        }
        // 自动拆列
        List<List<com.weavelay.core.model.OcrRow>> cols = OcrUtils.detectColumns(group);
        // 高亮
        weavePreview.setHighlightRows(group);
        // 右侧面板
        showRowGroupInPanel(startRow, endRow, group, cols);
        // 记录待绑定
        pendingRowStart = startRow;
        pendingRowEnd = endRow;
        pendingRowGroup = group;
        pendingRegion = null;
        
        pendingDigits = "";
        setStatus(String.format("已选中行 %d~%d (%d行 %d列) — 输入输出输出字段序号后回车绑定, Esc取消",
                startRow, endRow, group.size(), cols.size()));
    }


    /**
     * 更新右侧版式区域列表：每个区域一行标题 + 缩进的 OCR 文字行。
     */

    /**
     * 在右侧面板展示选中行范围（第一行=列头，自动拆列）。
     */
    private void showRowGroupInPanel(int startRow, int endRow,
                                     List<com.weavelay.core.model.OcrRow> rows,
                                     List<List<com.weavelay.core.model.OcrRow>> columns) {
        if (workMode == AppPreferences.WorkMode.APPLY) {
            showRuleArea(false);
            return;
        }
        layoutListBox.getChildren().clear();
        showRuleArea(true);
        String hdr = "选中行 " + startRow + "-" + endRow + "  列头:行" + startRow + "  "
                + (rows.size() - columns.size()) + "数据行  " + columns.size() + "列";
        javafx.scene.control.Label title = new javafx.scene.control.Label(hdr);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #4CAF50;");
        layoutListBox.getChildren().add(title);

        // 表头行
        StringBuilder headerLine = new StringBuilder();
        for (int ci = 0; ci < columns.size(); ci++) {
            if (ci > 0) headerLine.append(" │ ");
            List<com.weavelay.core.model.OcrRow> col = columns.get(ci);
            String h = col.isEmpty() ? "?" : col.get(0).getFeature();
            if (h.length() > 14) h = h.substring(0, 11) + "...";
            headerLine.append(h);
        }
        layoutListBox.getChildren().add(label("  " + headerLine, "#1565C0", 11));

        // 数据行
        int maxDataRows = 0;
        for (List<com.weavelay.core.model.OcrRow> col : columns) {
            maxDataRows = Math.max(maxDataRows, col.size() - 1);
        }
        for (int ri = 1; ri <= maxDataRows; ri++) {
            StringBuilder dataLine = new StringBuilder();
            for (int ci = 0; ci < columns.size(); ci++) {
                if (ci > 0) dataLine.append(" │ ");
                List<com.weavelay.core.model.OcrRow> col = columns.get(ci);
                String v = ri < col.size() ? col.get(ri).getFeature() : "";
                if (v.length() > 14) v = v.substring(0, 11) + "...";
                dataLine.append(v.isEmpty() ? "·" : v);
            }
            layoutListBox.getChildren().add(label("  " + dataLine, "#555", 11));
        }
    }


    private static javafx.scene.control.Label label(String text, String color, int size) {
        javafx.scene.control.Label l = new javafx.scene.control.Label(text);
        l.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: " + color + "; -fx-padding: 0 0 0 8;");
        return l;
    }

    /**
     * 对 table 区域展示 Excel 网格。
     */

    /**
     * 添加手动 K/V 配对到右侧输出面板。
     */
    private void addKeyValueToOutput(String key, String value) {
        String code = "kv_" + (outputRoot.getChildren().size() + 1);
        // 保存 K/V 配对，labelRow=标签 OCR 行，valueRow=值 OCR 行
        SlotValue sv = new SlotValue(code, key, value, selectedKeyRow, lastClickedRow);
        javafx.scene.control.TreeItem<SlotValue> item = new javafx.scene.control.TreeItem<>(sv);
        outputRoot.getChildren().add(item);
        outputRoot.setExpanded(true);
        // 同时高亮显示这个配对关系
        weavePreview.setHighlightRows(List.of(selectedKeyRow, lastClickedRow));
        selectedKeyRow = null;
    }

    /** 填入输出面板。走本地 LaTeX→可读文本（OCR/公式路径）。 */
    private void fillSlotValue(String slotCode, String value) {
        String plain = com.weavelay.core.formula.LatexToPlainText.convert(value);
        writeSlotValue(slotCode, plain);
    }

    private void writeSlotValue(String slotCode, String value) {
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            SlotValue sv = ti.getValue();
            if (slotCode.equals(sv.getSlotCode())) {
                ti.setValue(new SlotValue(sv.getSlotCode(), sv.getSlotLabel(), value,
                        sv.getLabelRow(), sv.getValueRow()));
                return;
            }
        }
    }

    /**
     * 表格校验结果弹窗。
     * 有 label 列头（*表名:col1,col2=...）→ TableRecognizer 按坐标聚类分行分列；
     * 无 label 列头 → TableBorderDetector 视觉检测格子，row 0 当 header。
     *
     * @param slotDefLabel SlotDefinition 的完整 label，含 *表名:列头=...
     */
    private void showTablePopup(String slotDefLabel, List<com.weavelay.core.layout.CellRect> cells,
                                List<OcrRow> ocrRows, int rx, int ry) {
        TablePopupManager.show(slotDefLabel, cells, ocrRows, rx, ry);
    }

    /**
     * 保存像素坐标提取规则：用 UPDATE 精确更新，不删不插不覆盖其他字段。
     */
    private void saveRegionRule(WeavePageResult page, String slotCode, String slotLabel, RectRegion region) {
        try {
            String kindCode = page.getPageKindCode();
            if (kindCode == null || kindCode.isEmpty() || "unknown".equals(kindCode)) {
                kindCode = "default";
            }
            int rx = (int) region.getX(), ry = (int) region.getY();
            int rw = (int) region.getWidth(), rh = (int) region.getHeight();
            runtime.getDatabase().updateSlotRegion(kindCode, slotCode, rx, ry, rw, rh);
            runtime.reloadCatalog();
        } catch (Exception ex) {
            System.err.println("weavelay: save region rule failed: " + ex.getMessage());
        }
    }

    /**
     * 筛选落在指定布局区域内的 OCR 行（中心点判定）。
     */

    private static String toHex(javafx.scene.paint.Color c) {
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    private void refreshSlotLabelsForPreview() {
        // 只列顶层输出项：普通字段显示字段名，表格只显示表名（绑整表，不列列字段）
        java.util.List<String> labels = new java.util.ArrayList<>();
        int n = 0;
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) continue;
            SlotValue top = ti.getValue();
            String topName = top.getSlotLabel() == null ? "" : top.getSlotLabel().trim();
            boolean isTable = !ti.getChildren().isEmpty()
                    || (top.getSlotCode() != null && applyTableHeaders.containsKey(top.getSlotCode()))
                    || topName.startsWith("*");
            if (isTable && topName.startsWith("*")) {
                TableLabelParser.ParsedTable pt = TableLabelParser.parse(topName);
                if (pt.tableName() != null && !pt.tableName().isBlank()) {
                    topName = pt.tableName().trim();
                }
            }
            n++;
            labels.add(n + ". " + topName);
        }
        weavePreview.setSlotLabels(labels);
    }

    /** 框选列表索引 = 输出树顶层节点（表格只绑父节点，不绑列）。 */
    private void bindRegionToTopLevelSlot(int topIdx) {
        if (pendingRegion == null) return;
        if (topIdx < 0 || topIdx >= outputRoot.getChildren().size()) return;
        javafx.scene.control.TreeItem<SlotValue> item = outputRoot.getChildren().get(topIdx);
        if (item == null || item.getValue() == null) return;
        if (workMode == AppPreferences.WorkMode.APPLY) {
            reRecognizeFromPendingRegionForItem(item);
            return;
        }
        int flatIdx = SlotTableHelper.flatIndexOf(outputRoot, item.getValue());
        if (flatIdx < 0) return;
        bindRegionToSlot(flatIdx);
    }

    private void handleKeyPress(javafx.scene.input.KeyEvent e) {
        if (workMode == AppPreferences.WorkMode.APPLY) {
            // 应用模式不处理 K/V / 数字绑定等模板快捷键（空格应用等走 handlePageShortcut）
            return;
        }
        // K/V 配对模式
        if (e.getCode() == javafx.scene.input.KeyCode.K && lastClickedRow != null) {
            selectedKeyRow = lastClickedRow;
            weavePreview.setHighlightRows(List.of(selectedKeyRow));
            setStatus("标签: " + selectedKeyRow.getFeature() + " — 请点击值行, 按V确认");
            e.consume();
            return;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.V && selectedKeyRow != null && lastClickedRow != null
                && lastClickedRow != selectedKeyRow) {
            String keyText = selectedKeyRow.getFeature().trim();
            String valueText = lastClickedRow.getFeature().trim();
            addKeyValueToOutput(keyText, valueText);
            // 高亮 K/V 对
            weavePreview.setHighlightRows(List.of(selectedKeyRow, lastClickedRow));
            setStatus("已添加: " + keyText + " = " + valueText);
            selectedKeyRow = null;
            e.consume();
            return;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            selectedKeyRow = null;
            // 同时取消框选
            if (pendingRegion != null) {
                pendingRegion = null;
                weavePreview.clearPendingSelection();
                weavePreview.setSlotLabels(null);
            }
            if (pendingRowGroup != null) {
                pendingRowGroup = null;
                pendingRowStart = 0;
                pendingRowEnd = 0;
                pendingDigits = "";
                weavePreview.setHighlightRows(null);
            }
            dismissOutputFocus(false);
            setStatus("已取消");
            e.consume();
            return;
        }
        // 行号范围模式（输入格式: 5-12, Shift+点击OCR行后使用）
        if (e.getCode() == javafx.scene.input.KeyCode.MINUS || e.getText().equals("-")) {
            if (!pendingDigits.isEmpty() && !pendingDigits.contains("-")) {
                pendingDigits += "-";
                pendingRowStart = Integer.parseInt(pendingDigits.replace("-", ""));
                setStatus("行范围 " + pendingRowStart + "-?  输入结束行号后回车 (Esc取消)");
            }
            e.consume();
            return;
        }

        if (pendingRowStart == 0) return;

        if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
            if (pendingDigits.isEmpty()) return;
            // 待绑定行组 → 直接绑到输出字段
            if (pendingRowGroup != null && !pendingRowGroup.isEmpty()) {
                int idx = Integer.parseInt(pendingDigits) - 1;
                pendingDigits = "";
                if (idx >= 0 && idx < SlotTableHelper.flatOutputItems(outputRoot).size()) {
                    bindRowGroupToSlotIdx(idx);
                }
                e.consume();
                return;
            }
            // 行范围绑定
            if (pendingRowStart > 0 && pendingDigits.contains("-")) {
                String endStr = pendingDigits.substring(pendingDigits.indexOf('-') + 1);
                if (!endStr.isEmpty()) {
                    pendingRowEnd = Integer.parseInt(endStr);
                    bindRowGroupToSlot();
                }
                pendingDigits = "";
                e.consume();
                return;
            }
        }

        String digit = keyDigit(e.getCode());
        if (digit != null) {
            pendingDigits += digit;
            if (pendingRowStart > 0 && pendingDigits.contains("-")) {
                setStatus("行范围 " + pendingDigits + "  回车确认, Esc取消");
            }
            e.consume();
        }
    }

    private void bindRegionToSlot(int idx) {
        if (pendingRegion == null) return;

        // 表格父节点：只存坐标，不跑 OCR
        javafx.scene.control.TreeItem<SlotValue> item = SlotTableHelper.findTreeItemAtFlatIndex(outputRoot,idx);
        if (item != null && !item.getChildren().isEmpty()) {
            bindRegionToSingleSlot(idx);
            return;
        }

        bindRegionToSingleSlot(idx);
    }

    /**
     * 单槽填充: 框选区域所有文字合并为一个字符串.
     */
    private void bindRegionToSingleSlot(int idx) {
        WeavePageResult page = currentPageOrNull();
        if (page == null) return;
        SlotValue old = SlotTableHelper.getFlatOutputItem(outputRoot,idx);
        String code = old.getSlotCode();
        String label = old.getSlotLabel();

        // 子列的话坐标存到父级，不填值
        String saveCode = code;
        String saveLabel = label;
        boolean isTableField = false;
        if (code != null && code.matches(".*_c\\d+$")) {
            saveCode = code.replaceAll("_c\\d+$", "");
        }
        // 检查父级或自身是否是表格（label 以 * 开头 或 有子节点）
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (saveCode.equals(ti.getValue().getSlotCode())) {
                saveLabel = ti.getValue().getSlotLabel();
                isTableField = !ti.getChildren().isEmpty()
                        || (saveLabel != null && saveLabel.startsWith("*"));
                break;
            }
        }
        // 自身 label 也检测
        if (!isTableField && label != null && label.startsWith("*")) {
            isTableField = true;
        }

        try {
            // 表格字段：不跑 OCR，只记坐标
            if (isTableField) {
                SlotTableHelper.setFlatOutputItem(outputRoot,idx, new SlotValue(code, label, "", old.getLabelRow(), old.getValueRow()));
                if (runtime != null) {
                    saveRegionRule(page, saveCode, saveLabel, pendingRegion);
                }
                setStatus(String.format("已绑定表格坐标: %s @(%d,%d)",
                        saveLabel, (int) pendingRegion.getX(), (int) pendingRegion.getY()));
            } else {
                // 普通字段：跑 OCR 填值
                List<OcrRow> rows = OcrUtils.extractRegionOcrRows(page, selectedPath, currentPageIndex, pendingRegion, ocrService);
                StringBuilder sb = new StringBuilder();
                for (OcrRow r : rows) {
                    if (r.getFeature() != null && !r.getFeature().trim().isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(r.getFeature().trim());
                    }
                }
                String text = sb.toString();
                SlotTableHelper.setFlatOutputItem(outputRoot,idx, new SlotValue(code, label, text, old.getLabelRow(), old.getValueRow()));
                if (runtime != null) {
                    saveRegionRule(page, saveCode, saveLabel, pendingRegion);
                }
                setStatus(String.format("已绑定: %s @(%d,%d) = %s",
                        label, (int) pendingRegion.getX(), (int) pendingRegion.getY(),
                        text.isEmpty() ? "(空)" : text.substring(0, Math.min(30, text.length()))));
            }
            pendingRegion = null;
            weavePreview.clearPendingSelection();
        } catch (Exception ex) {
            setStatus("提取失败: " + ex.getMessage());
        }
    }

    /**
     * 表格列填充: 框选区域内每个 OCR 文本块用 | 拼接为一行, 按序号追加到列表.
     */
    private void bindRegionToListColumns(javafx.scene.control.TreeItem<SlotValue> parentItem) {
        WeavePageResult page = currentPageOrNull();
        if (page == null) return;
        try {
            List<OcrRow> rows = OcrUtils.extractRegionOcrRows(page, selectedPath, currentPageIndex, pendingRegion, ocrService);
            if (rows.isEmpty()) {
                setStatus("框选区域未识别到文字");
                return;
            }

            // 拼接所有文本块: text1 | text2 | text3
            StringBuilder sb = new StringBuilder();
            for (OcrRow r : rows) {
                String text = r.getFeature();
                if (text != null && !text.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(text.trim());
                }
            }
            String rowData = sb.toString();
            if (rowData.isEmpty()) {
                setStatus("框选区域未识别到文字");
                return;
            }

            // 计算行号 (第一个子是列头, size=1时第一条数据, size=2时第二条...)
            int newRowNum = parentItem.getChildren().size();

            // 添加子行: label="1.", value="OP10 | 车削 | ..."
            String rowLabel = newRowNum + ".";
            SlotValue parentVal = parentItem.getValue();
            String rowCode = parentVal.getSlotCode() + "_row_" + newRowNum;
            parentItem.getChildren().add(new javafx.scene.control.TreeItem<>(new SlotValue(
                    rowCode, rowLabel, rowData, null, null)));

            // 更新父节点标签显示条数
            String parentLabel = parentVal.getSlotLabel();
            int parenIdx = parentLabel.indexOf(" (共");
            String baseName = parenIdx > 0 ? parentLabel.substring(0, parenIdx) : parentLabel;
            parentItem.setValue(new SlotValue(
                    parentVal.getSlotCode(),
                    baseName + " (共" + newRowNum + "条)",
                    "", null, null));

            // 去掉编号前缀用于状态显示
            int spaceIdx = baseName.indexOf(' ');
            String displayName = spaceIdx > 0 ? baseName.substring(spaceIdx + 1) : baseName;
            setStatus(String.format("已绑定到表格 [%s] 第 %d 行: %s",
                    displayName, newRowNum, rowData));
            pendingRegion = null;
            weavePreview.clearPendingSelection();
        } catch (Exception ex) {
            setStatus("表格填充失败: " + ex.getMessage());
        }
    }

    private void bindRowGroupToSlotIdx(int idx) {
        if (pendingRowGroup == null || pendingRowGroup.isEmpty()) return;
        String code = "@rows:" + pendingRowStart + "-" + pendingRowEnd;
        StringBuilder sb = new StringBuilder();
        for (com.weavelay.core.model.OcrRow row : pendingRowGroup) {
            String f = row.getFeature();
            if (f != null && !f.trim().isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(f.trim());
            }
        }
        SlotValue old = SlotTableHelper.getFlatOutputItem(outputRoot,idx);
        SlotTableHelper.setFlatOutputItem(outputRoot,idx, new SlotValue(code, "行组 " + pendingRowStart + "-" + pendingRowEnd,
                sb.toString(), null, null));
        persistLayoutBinding(idx, code);
        setStatus(String.format("行组 %d~%d 已绑定到输出输出字段 (code: %s)",
                pendingRowStart, pendingRowEnd, code));
        pendingRowStart = 0;
        pendingRowEnd = 0;
        pendingDigits = "";
        pendingRowGroup = null;
    }

    /**
     * 手动行范围绑定：用户输入 5-12 表示第 5~12 行 OCR 文字打包为一个输出字段。
     */
    private void bindRowGroupToSlot() {
        if (pendingRowStart <= 0 || pendingRowEnd <= 0 || pendingRowStart > pendingRowEnd) {
            setStatus("行范围无效: " + pendingRowStart + "-" + pendingRowEnd);
            pendingRowStart = 0;
            pendingRowEnd = 0;
            pendingDigits = "";
            return;
        }
        WeavePageResult page = currentPageOrNull();
        if (page == null) return;
        List<com.weavelay.core.model.OcrRow> allRows = page.getOcrRows();
        if (allRows.isEmpty()) return;

        // 收集范围内的 OCR 行
        List<com.weavelay.core.model.OcrRow> groupRows = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = pendingRowStart - 1; i < pendingRowEnd && i < allRows.size(); i++) {
            com.weavelay.core.model.OcrRow row = allRows.get(i);
            groupRows.add(row);
            String f = row.getFeature();
            if (f != null && !f.trim().isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(f.trim());
            }
        }

        // 高亮选中的行
        weavePreview.setHighlightRows(groupRows);

        // 自动检测列：按 X 坐标聚类
        List<List<com.weavelay.core.model.OcrRow>> columns = OcrUtils.detectColumns(groupRows);
        showRowGroupInPanel(pendingRowStart, pendingRowEnd, groupRows, columns);

        // 生成输出字段码
        String code = "@rows:" + pendingRowStart + "-" + pendingRowEnd;
        int slotIdx = pendingRowStart > 0 ? 0 : 0; // 需要用户选择绑到哪个输出输出字段

        // 直接绑定到等待中的输出序号
        String waitDigits = pendingDigits.replace("-", "").replace(String.valueOf(pendingRowStart), "")
                .replace(String.valueOf(pendingRowEnd), "");
        if (!waitDigits.isEmpty()) {
            slotIdx = Integer.parseInt(waitDigits) - 1;
            waitDigits = "";
        }

        // 如果没有明确的输出序号，提示用户
        if (slotIdx < 0 || pendingDigits.matches("\\d+-\\d+")) {
            setStatus(String.format("行组 %d~%d (%d行): %s — 输入输出输出字段序号后回车绑定",
                    pendingRowStart, pendingRowEnd, groupRows.size(),
                    sb.length() > 60 ? sb.substring(0, 57) + "..." : sb.toString()));
            pendingRowGroup = groupRows;
            return;
        }

        // 有输出字段序号，直接绑定
        if (slotIdx >= 0 && slotIdx < SlotTableHelper.flatOutputItems(outputRoot).size()) {
            SlotValue old = SlotTableHelper.getFlatOutputItem(outputRoot,slotIdx);
            SlotTableHelper.setFlatOutputItem(outputRoot,slotIdx, new SlotValue(code, "行组 " + pendingRowStart + "-" + pendingRowEnd,
                    sb.toString(), null, null));
            persistLayoutBinding(slotIdx, code);
            setStatus(String.format("行组 %d~%d 已绑定到输出输出字段 (code: %s)",
                    pendingRowStart, pendingRowEnd, code));
        }

        pendingRowStart = 0;
        pendingRowEnd = 0;
        pendingDigits = "";
        pendingRowGroup = null;
    }


    private void persistLayoutBinding(int flatIdx, String regionCode) {
        if (runtime == null) return;
        try {
            String familyCode = runtime.getFamilyCode();
            java.util.List<String[]> rows = runtime.getDatabase().loadFamilyOutputFields(familyCode);
            if (flatIdx < 0 || flatIdx >= rows.size()) return;
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<String> types = new java.util.ArrayList<>();
            java.util.List<String> headers = new java.util.ArrayList<>();
            for (String[] row : rows) {
                names.add(row[0]);
                types.add(row[1]);
                headers.add(row.length > 2 ? row[2] : "");
            }
            names.set(flatIdx, regionCode);
            runtime.getDatabase().saveFamilyOutputFields(familyCode, names, types, headers);
        } catch (Exception ex) {
        }
    }

    private static String keyDigit(javafx.scene.input.KeyCode code) {
        switch (code) {
            case DIGIT0:
            case NUMPAD0:
                return "0";
            case DIGIT1:
            case NUMPAD1:
                return "1";
            case DIGIT2:
            case NUMPAD2:
                return "2";
            case DIGIT3:
            case NUMPAD3:
                return "3";
            case DIGIT4:
            case NUMPAD4:
                return "4";
            case DIGIT5:
            case NUMPAD5:
                return "5";
            case DIGIT6:
            case NUMPAD6:
                return "6";
            case DIGIT7:
            case NUMPAD7:
                return "7";
            case DIGIT8:
            case NUMPAD8:
                return "8";
            case DIGIT9:
            case NUMPAD9:
                return "9";
            default:
                return null;
        }
    }

    private void applyOutputHighlight(SlotValue selected) {
        WeavePageResult page = currentPageOrNull();
        if (selected == null || page == null) {
            weavePreview.clearHighlight();
            weavePreview.clearValueChip();
            return;
        }
        boolean applyMode = workMode == AppPreferences.WorkMode.APPLY;
        // 表格：应用模式 PDF 点物理格旁白对照；模板模式只高亮区域、不弹旁白
        if (isTableSlotValue(selected)) {
            activeTableSlotCode = applyMode ? selected.getSlotCode() : null;
            weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
            ApplyTableCache cache = tableCacheForCode(selected.getSlotCode());
            List<OcrRow> regionHits = resolveRegionFromDatabase(selected, page);
            if (applyMode && cache != null && cache.cells() != null && !cache.cells().isEmpty()) {
                java.util.List<OcrRow> vcells =
                        buildTableVirtualCellRows(activeTableSlotCode, cache);
                weavePreview.setVirtualCellRows(vcells);
                weavePreview.setHighlightRows(regionHits);
                weavePreview.clearValueChip();
            } else {
                weavePreview.setHighlightRows(regionHits);
                weavePreview.clearValueChip();
            }
            return;
        }
        activeTableSlotCode = null;
        weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
        List<OcrRow> hits = resolveRegionFromDatabase(selected, page);
        if (hits.isEmpty()) {
            List<OcrRow> pageRows = page.getOcrRows();
            if (pageRows.isEmpty()) pageRows = weavePreview.getCurrentOcrRows();
            hits = resolveOutputHighlight(selected, pageRows);
        }
        weavePreview.setHighlightRows(hits);
        if (!applyMode) {
            weavePreview.clearValueChip();
            return;
        }
        String value = selected.getValue();
        OcrRow anchor = null;
        if (selected.getValueRow() != null && selected.getValueRow().hasBbox()) {
            anchor = selected.getValueRow();
        } else {
            for (OcrRow h : hits) {
                if (h != null && h.hasBbox()) {
                    anchor = h;
                    break;
                }
            }
        }
        String code = selected.getSlotCode();
        if (anchor != null && code != null && !code.isBlank()) {
            weavePreview.setValueChip(value == null ? "" : value, anchor, code);
        } else {
            weavePreview.clearValueChip();
        }
    }

    /** 表格槽位（勿与标量旁白框混用）。 */
    private boolean isTableSlotValue(SlotValue sv) {
        if (sv == null) {
            return false;
        }
        String label = sv.getSlotLabel();
        if (label != null && label.startsWith("*")) {
            return true;
        }
        if (com.weavelay.app.export.ConfirmExcelWriter.looksLikeMarkdownTable(sv.getValue())) {
            return true;
        }
        String code = sv.getSlotCode();
        if (code != null && applyTableHeaders.containsKey(code)) {
            return true;
        }
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) {
                continue;
            }
            if (code != null && code.equals(ti.getValue().getSlotCode()) && !ti.getChildren().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 预览旁白框里改表格某一格 → 写回输出树 markdown。 */
    private void applyTableCellChipEdit(String slotCode, int dataRow, int dataCol, String newValue) {
        if (slotCode == null || slotCode.isBlank() || dataRow < 0 || dataCol < 0) {
            return;
        }
        String md = null;
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti != null && ti.getValue() != null && slotCode.equals(ti.getValue().getSlotCode())) {
                md = ti.getValue().getValue();
                break;
            }
        }
        String updated = updateMarkdownTableCell(md, dataRow, dataCol, newValue == null ? "" : newValue);
        fillSlotValue(slotCode, updated);
        if (currentPageIndex >= 0) {
            outputCache.put(currentPageIndex, new java.util.ArrayList<>(outputRoot.getChildren()));
        }
        if (outputTable != null) {
            outputTable.refresh();
        }
        ApplyTableCache cache = tableCacheForCode(slotCode);
        if (cache != null) {
            weavePreview.setVirtualCellRows(buildTableVirtualCellRows(slotCode, cache));
        }
        setStatus("已从预览旁白改写表格单元格");
    }

    private static String updateMarkdownTableCell(String md, int dataRow, int dataCol, String newValue) {
        java.util.List<java.util.List<String>> rows =
                com.weavelay.app.export.ConfirmExcelWriter.parseMarkdownTable(md);
        if (rows.isEmpty()) {
            return md == null ? "" : md;
        }
        String title = "表格";
        if (md != null) {
            for (String line : md.split("\n")) {
                String t = line.trim();
                if (t.startsWith("### ")) {
                    title = t.substring(4).trim();
                    break;
                }
            }
        }
        int mdRow = dataRow + 1;
        while (rows.size() <= mdRow) {
            int cols = rows.get(0).size();
            java.util.List<String> blank = new java.util.ArrayList<>();
            for (int i = 0; i < cols; i++) {
                blank.add("");
            }
            rows.add(blank);
        }
        java.util.List<String> line = new java.util.ArrayList<>(rows.get(mdRow));
        int cols = Math.max(rows.get(0).size(), dataCol + 1);
        while (line.size() < cols) {
            line.add("");
        }
        line.set(dataCol, newValue);
        rows.set(mdRow, line);
        int maxCol = 0;
        for (java.util.List<String> r : rows) {
            maxCol = Math.max(maxCol, r.size());
        }
        String[][] grid = new String[rows.size()][maxCol];
        for (int r = 0; r < rows.size(); r++) {
            java.util.List<String> src = rows.get(r);
            for (int c = 0; c < maxCol; c++) {
                grid[r][c] = c < src.size() && src.get(c) != null ? src.get(c) : "";
            }
        }
        return com.weavelay.app.MarkdownUtils.gridToMarkdown(grid, rows.size(), maxCol, title);
    }

    /** 预览旁白框里直接改标量值 → 写回输出树。 */
    private void applyValueChipEdit(String slotCode, String newValue) {
        if (slotCode == null || slotCode.isBlank()) {
            return;
        }
        fillSlotValue(slotCode, newValue == null ? "" : newValue);
        if (currentPageIndex >= 0) {
            outputCache.put(currentPageIndex, new java.util.ArrayList<>(outputRoot.getChildren()));
        }
        if (outputTable != null) {
            outputTable.refresh();
        }
        setStatus("已从预览旁白改写字段");
    }

    /** 从 OCR 行列表中匹配与 slot 值相关的行。 */
    private static List<OcrRow> resolveOutputHighlight(SlotValue selected, List<OcrRow> pageRows) {
        if (selected == null || pageRows == null || pageRows.isEmpty()) {
            return List.of();
        }
        String value = selected.getValue();
        List<OcrRow> result = new java.util.ArrayList<>();
        for (OcrRow row : pageRows) {
            if (row == null || row.getFeature() == null) continue;
            if (value != null && !value.isEmpty() && row.getFeature().contains(value)) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 从数据库加载 SlotDefinition 的像素坐标区域，直接构造高亮框。
     */
    private List<OcrRow> resolveRegionFromDatabase(SlotValue slot, WeavePageResult page) {
        String slotCode = slot.getSlotCode();
        if (slotCode == null) {
            return List.of();
        }
        if (runtime == null) {
            return List.of();
        }
        String kindCode = page.getPageKindCode();
        if (kindCode == null || "unknown".equals(kindCode)) {
            return List.of();
        }
        String lookupCode = slotCode;
        if (lookupCode.matches(".*_c\\d+$")) {
            lookupCode = lookupCode.replaceAll("_c\\d+$", "");
        }
        RectRegion override = getApplyRegionOverride(currentPageIndex, lookupCode);
        if (override != null && override.getWidth() > 0 && override.getHeight() > 0) {
            int rx = (int) override.getX();
            int ry = (int) override.getY();
            int rw = (int) override.getWidth();
            int rh = (int) override.getHeight();
            return List.of(new com.weavelay.core.model.OcrRow(
                    page.getStreamName(), "",
                    0.0,
                    rx, ry, rx + rw, ry + rh,
                    null));
        }
        try {
            for (SlotDefinition sd : runtime.getDatabase().loadSlots(kindCode)) {
                if (sd.getSlotCode().equals(lookupCode)) {
                    if (sd.hasRegion()) {
                        return List.of(new com.weavelay.core.model.OcrRow(
                                page.getStreamName(), "",
                                0.0,
                                sd.getRegionX(), sd.getRegionY(),
                                sd.getRegionX() + sd.getRegionW(),
                                sd.getRegionY() + sd.getRegionH(),
                                null));
                    }
                }
            }
        } catch (Exception e) {
        }
        return List.of();
    }

    private WeavePageResult currentPageOrNull() {
        if (sessionPages.isEmpty() || currentPageIndex < 0 || currentPageIndex >= sessionPages.size()) {
            return null;
        }
        return sessionPages.get(currentPageIndex);
    }

    private void restoreLastInput() {
        Path path = preferences.getLastInputPath();
        AppPreferences.InputKind kind = preferences.getLastInputKind();
        if (path == null || kind == null) {
            return;
        }
        if (kind == AppPreferences.InputKind.PDF_FOLDER && Files.isDirectory(path)) {
            openFolder(path, false);
        } else if (kind == AppPreferences.InputKind.PDF_FILE && Files.isRegularFile(path)) {
            Path parent = path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                folderPath = parent;
                folderNameLabel.setText(parent.getFileName().toString());
                inputKind = AppPreferences.InputKind.PDF_FOLDER;
                setFileNavButtonsVisible(true);
                selectAndLoadPdf(path);
            }
        }
    }

    private void chooseFolder(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 PDF 文件夹");
        Path initial = preferences.resolveInitialBrowseDirectory();
        if (initial != null) {
            chooser.setInitialDirectory(initial.toFile());
        }
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            openFolder(dir.toPath(), true);
        }
    }

    /** 选择文件夹后自动打开：优先恢复上次同目录下的断点文件，否则第一份 PDF。 */
    private void openFolder(Path dir, boolean persist) {
        folderPath = dir;
        inputKind = AppPreferences.InputKind.PDF_FOLDER;
        if (persist) {
            preferences.rememberInput(dir, AppPreferences.InputKind.PDF_FOLDER);
        }
        folderNameLabel.setText(dir.getFileName().toString());
        setFileNavButtonsVisible(true);
        List<Path> pdfs = listPdfsInFolder(dir);
        if (pdfs.isEmpty()) {
            selectedPath = null;
            fileNameLabel.setText("");
            clearDocumentCaches();
            sessionPages.clear();
            weavePreview.clear();
            setStatus("文件夹内无 PDF");
            return;
        }
        Path resume = preferences.getLastWorkFile();
        Path toOpen = pdfs.get(0);
        if (resume != null) {
            Path resumeNorm = resume.toAbsolutePath().normalize();
            for (Path pdf : pdfs) {
                if (pdf.toAbsolutePath().normalize().equals(resumeNorm)) {
                    toOpen = pdf;
                    break;
                }
            }
        }
        selectAndLoadPdf(toOpen);
    }

    private static List<Path> listPdfsInFolder(Path folder) {
        List<Path> pdfs = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return pdfs;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(folder)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .forEach(pdfs::add);
        } catch (Exception ignored) {
        }
        return pdfs;
    }

    /**
     * 文件夹模式下切换相邻 PDF：{@code delta=+1} 下一份，{@code -1} 上一份（循环）。
     */
    private void switchToAdjacentFile(int delta) {
        Path folder = folderPath;
        if (folder == null || !Files.isDirectory(folder)) {
            if (selectedPath != null) {
                folder = selectedPath.getParent();
            }
        }
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        List<Path> pdfs = listPdfsInFolder(folder);
        if (pdfs.isEmpty()) {
            setStatus("文件夹内无 PDF");
            return;
        }
        int curIdx = -1;
        if (selectedPath != null) {
            for (int i = 0; i < pdfs.size(); i++) {
                if (pdfs.get(i).equals(selectedPath)) {
                    curIdx = i;
                    break;
                }
            }
        }
        int n = pdfs.size();
        int nextIdx = curIdx < 0 ? 0 : Math.floorMod(curIdx + delta, n);
        Path nextPath = pdfs.get(nextIdx);
        setStatus("已切换到: " + nextPath.getFileName() + " (" + (nextIdx + 1) + "/" + n + ")");
        selectAndLoadPdf(nextPath);
    }

    private void setFileNavButtonsVisible(boolean visible) {
        if (prevFileBtn != null) {
            prevFileBtn.setVisible(visible);
            prevFileBtn.setManaged(visible);
        }
        if (nextFileBtn != null) {
            nextFileBtn.setVisible(visible);
            nextFileBtn.setManaged(visible);
        }
    }

    private void selectAndLoadPdf(Path pdf) {
        selectedPath = pdf;
        inputKind = AppPreferences.InputKind.PDF_FOLDER;
        if (folderPath == null && pdf.getParent() != null) {
            folderPath = pdf.getParent();
        }
        if (folderPath != null) {
            folderNameLabel.setText(folderPath.getFileName().toString());
            preferences.rememberInput(folderPath, AppPreferences.InputKind.PDF_FOLDER);
        }
        preferences.rememberWorkFile(pdf);
        List<Path> pdfs = listPdfsInFolder(folderPath);
        int idx = pdfs.indexOf(pdf);
        if (idx >= 0) {
            fileNameLabel.setText(pdf.getFileName() + " (" + (idx + 1) + "/" + pdfs.size() + ")");
        } else {
            fileNameLabel.setText(pdf.getFileName().toString());
        }
        setFileNavButtonsVisible(true);
        loadPdfPages();
    }

    private void loadPdfPages() {
        if (weaveWorker != null && weaveWorker.isAlive()) {
            return;
        }
        if (selectedPath == null || !Files.isRegularFile(selectedPath)) {
            setStatus("请先选择 PDF 文件夹");
            return;
        }
        setStatus("加载中...");
        prevPageBtn.setDisable(true);
        nextPageBtn.setDisable(true);
        clearDocumentCaches();
        sessionPages.clear();
        currentPageIndex = 0;
        weavePreview.clear();
        refreshPickerOptions(null);

        final Path pdf = selectedPath;
        Thread worker = new Thread(() -> {
            try {
                Platform.runLater(() -> setStatus("渲染 PDF 页面..."));
                List<WeavePageResult> pages = batchService.renderPdfPagesOnly(pdf);
                Platform.runLater(() -> {
                    sessionPages.addAll(pages);
                    if (!sessionPages.isEmpty()) {
                        showPage(0);
                    }
                    updatePageNav();
                    if (applyBtn != null) {
                        applyBtn.setDisable(false);
                    }
                    setStatus(pages.size() + " 页已加载");
                    finishWeaveRun();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setStatus("失败: " + ex.getMessage());
                    finishWeaveRun();
                });
            }
        }, "weavelay-render");
        weaveWorker = worker;
        worker.setDaemon(true);
        worker.start();
    }

    /** 换 PDF 文件时清空按页/应用相关缓存，避免跨文件堆积与串值。 */
    private void clearDocumentCaches() {
        outputCache.clear();
        applyTableCacheByPage.clear();
        applyRegionOverridesByPage.clear();
        applyTableHeaders.clear();
        outputRoot.getChildren().clear();
        lastEditField = null;
        pendingRegion = null;
        try {
            outputJsonEngine.reset("");
        } catch (Exception ignored) {
        }
        refreshOutputJsonViewerIfOpen();
        if (applyElapsedLabel != null) {
            applyElapsedLabel.setText("");
        }
        showRuleArea(false);
    }

    private void finishWeaveRun() {
        weaveWorker = null;
    }

    /** 刷新快捷键提示: 包含页面类型编号映射. */
    private void refreshShortcutHint() {
        if (shortcutHint == null || pageKindCombo == null) return;
        StringBuilder sb = new StringBuilder("← → 翻页 | F2 选页面类型 | Space 应用 | 框选后点字段重识 | "
                + IndustrialSymbolPicker.hotkeyLabel() + " 特殊键盘 | Esc/右键 取消");
        javafx.application.Platform.runLater(() -> shortcutHint.setText(sb.toString()));
    }

    /** 刷新页面类型下拉框: 加载当前 family 的所有页面类型, 并选中当前页已有的类型. */
    private void refreshPageKindCombo(WeavePageResult page) {
        if (pageKindCombo == null || runtime == null) return;
        String familyCode = runtime.getFamilyCode();
        try {
            java.util.List<com.weavelay.core.store.PageKindRecord> kinds =
                    runtime.getDatabase().listPageKinds(familyCode);
            pageKindCombo.getItems().setAll(kinds);
            refreshShortcutHint();
            if (page == null) return;
            // 自动选中当前页已有的类型
            String kindCode = page.getPageKindCode();
            if (kindCode != null && !"unknown".equals(kindCode)) {
                for (com.weavelay.core.store.PageKindRecord k : kinds) {
                    if (k.getCode().equals(kindCode)) {
                        pageKindCombo.setValue(k);
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            // ignore
        }
    }

    /** 手动选择页面类型后更新页面和标签, 并加载对应输出字段. */
    private void applyPageKind(com.weavelay.core.store.PageKindRecord kind) {
        WeavePageResult page = currentPageOrNull();
        if (page == null || kind == null) return;
        sessionPages.set(currentPageIndex, page.withPageKind(kind.getCode(), kind.getDisplayName()));
        // 换页型：重建字段骨架，value 清空（不沿用其它页残留）
        applyTableCacheByPage.remove(currentPageIndex);
        applyRegionOverridesByPage.remove(currentPageIndex);
        pendingRegion = null;
        if (weavePreview != null) {
            weavePreview.clearPendingSelection();
            weavePreview.setSlotLabels(null);
        }
        refreshOutputFromFamily();
    }

    /** 应用模式框选后点字段：升到顶层槽再重识。 */
    private void reRecognizeFromPendingRegionForItem(javafx.scene.control.TreeItem<SlotValue> item) {
        if (pendingRegion == null || item == null || item.getValue() == null) {
            return;
        }
        javafx.scene.control.TreeItem<SlotValue> top = item;
        while (top.getParent() != null && top.getParent() != outputRoot) {
            top = top.getParent();
        }
        reRecognizeFromPendingRegion(top.getValue());
    }

    /**
     * 应用模式：用当前框选区域重识单个槽位，写入输出与会话覆盖，不写库。
     */
    private void reRecognizeFromPendingRegion(SlotValue topSlot) {
        if (workMode != AppPreferences.WorkMode.APPLY || pendingRegion == null || topSlot == null) {
            return;
        }
        WeavePageResult page = currentPageOrNull();
        if (page == null) {
            setStatus("请先加载PDF");
            return;
        }
        if (pageKindCombo == null || pageKindCombo.getValue() == null || runtime == null) {
            setStatus("请先选择页面类型");
            return;
        }
        String kindCode = pageKindCombo.getValue().getCode();
        String slotCode = topSlot.getSlotCode();
        if (slotCode == null || slotCode.isBlank()) {
            setStatus("无效字段");
            return;
        }
        com.weavelay.core.page.SlotDefinition slotDef = null;
        try {
            for (com.weavelay.core.page.SlotDefinition s : runtime.getDatabase().loadSlots(kindCode)) {
                if (slotCode.equals(s.getSlotCode())) {
                    slotDef = s;
                    break;
                }
            }
        } catch (Exception ex) {
            setStatus("加载字段失败: " + ex.getMessage());
            return;
        }
        if (slotDef == null) {
            setStatus("未找到字段定义: " + topSlot.getSlotLabel());
            return;
        }

        final RectRegion region = pendingRegion;
        final int pageIdx = currentPageIndex;
        final byte[] png = page.getPagePng();
        final com.weavelay.core.page.SlotDefinition slot = slotDef;
        final String label = topSlot.getSlotLabel() != null ? topSlot.getSlotLabel() : slotCode;

        pendingRegion = null;
        weavePreview.clearPendingSelection();
        weavePreview.setSlotLabels(null);
        setStatus("重识中: " + label + "…");
        if (applyBtn != null) {
            applyBtn.setDisable(true);
        }

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                RapidOcrService.prepareNativeRuntime();
                java.awt.image.BufferedImage fullImg = null;
                if (png != null && png.length > 0) {
                    fullImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                }
                if (fullImg == null) {
                    javafx.application.Platform.runLater(() -> {
                        if (applyBtn != null) {
                            applyBtn.setDisable(false);
                        }
                        setStatus("重识失败: 无页面图像");
                    });
                    return;
                }
                int rx = (int) region.getX();
                int ry = (int) region.getY();
                int rw = (int) region.getWidth();
                int rh = (int) region.getHeight();
                SlotRecognizeResult result = recognizeOneSlot(
                        slot, fullImg, java.util.Collections.emptyList(), rx, ry, rw, rh);
                javafx.application.Platform.runLater(() -> {
                    putApplyRegionOverride(pageIdx, slotCode, region);
                    if (result != null) {
                        if (result.text() != null && !result.text().isEmpty()) {
                            fillSlotValue(slotCode, result.text());
                        }
                        if (result.tableCache() != null) {
                            applyTableCacheByPage
                                    .computeIfAbsent(pageIdx, k -> new java.util.LinkedHashMap<>())
                                    .put(slotCode, result.tableCache());
                        }
                    }
                    syncApplyTableColumnChildren();
                    if (outputTable != null) {
                        outputTable.refresh();
                    }
                    activateApplyTableVirtualCells();
                    if (applyBtn != null) {
                        applyBtn.setDisable(false);
                    }
                    String preview = result != null && result.text() != null ? result.text().trim() : "";
                    if (preview.length() > 40) {
                        preview = preview.substring(0, 40) + "…";
                    }
                    setStatus(String.format("已重识: %s @(%d,%d) %s（仅本次，未写回规则）",
                            label, rx, ry, preview.isEmpty() ? "(空)" : preview));
                });
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() -> {
                    if (applyBtn != null) {
                        applyBtn.setDisable(false);
                    }
                    setStatus("重识失败: " + ex.getMessage());
                });
            }
        });
    }

    private record SlotRecognizeResult(String text, ApplyTableCache tableCache) {}

    private void putApplyRegionOverride(int pageIdx, String slotCode, RectRegion region) {
        if (slotCode == null || region == null) {
            return;
        }
        applyRegionOverridesByPage
                .computeIfAbsent(pageIdx, k -> new java.util.HashMap<>())
                .put(slotCode, region);
    }

    private RectRegion getApplyRegionOverride(int pageIdx, String slotCode) {
        java.util.Map<String, RectRegion> pageMap = applyRegionOverridesByPage.get(pageIdx);
        if (pageMap == null || slotCode == null) {
            return null;
        }
        return pageMap.get(slotCode);
    }

    /** 会话覆盖优先，否则用库中绝对像素区域。 */
    private int[] resolveApplyRegion(int pageIdx, com.weavelay.core.page.SlotDefinition slot) {
        RectRegion override = getApplyRegionOverride(pageIdx, slot.getSlotCode());
        if (override != null && override.getWidth() > 0 && override.getHeight() > 0) {
            return new int[] {
                    (int) override.getX(), (int) override.getY(),
                    (int) override.getWidth(), (int) override.getHeight()
            };
        }
        return new int[] {
                slot.getRegionX(), slot.getRegionY(), slot.getRegionW(), slot.getRegionH()
        };
    }

    /**
     * 单槽裁图识别（文本 / 表 / 公式）。pageRows 可为空（单槽重识时走裁图 OCR）。
     */
    private SlotRecognizeResult recognizeOneSlot(
            com.weavelay.core.page.SlotDefinition slot,
            java.awt.image.BufferedImage fullImg,
            java.util.List<com.weavelay.core.model.OcrRow> pageRows,
            int rx, int ry, int rw, int rh) throws Exception {
        if (slot == null || fullImg == null || rw <= 0 || rh <= 0) {
            return null;
        }
        boolean isTable = slot.isTable()
                || (slot.getSlotLabel() != null && slot.getSlotLabel().startsWith("*"));
        java.util.List<com.weavelay.core.model.OcrRow> regionPageRows =
                OcrUtils.rowsInRegion(pageRows, rx, ry, rw, rh);
        if (!isTable && !slot.isFormulaField()) {
            String text = OcrUtils.ocrRowsToText(regionPageRows);
            if (text == null || text.isEmpty()) {
                java.awt.image.BufferedImage cropImg =
                        OcrUtils.cropToBufferedImage(fullImg, rx, ry, rw, rh);
                if (cropImg != null) {
                    java.util.List<com.weavelay.core.model.OcrRow> cropRows =
                            ocrService.recognizeBufferedImage(cropImg, "apply");
                    text = OcrUtils.ocrRowsToText(cropRows);
                }
            }
            if (text != null && !text.isEmpty()) {
                return new SlotRecognizeResult(text, null);
            }
            return null;
        }
        if (isTable) {
            java.awt.image.BufferedImage cropImg =
                    OcrUtils.cropToBufferedImage(fullImg, rx, ry, rw, rh);
            if (cropImg == null) {
                return null;
            }
            java.util.List<com.weavelay.core.model.OcrRow> cropRows =
                    OcrUtils.translateRowsToCrop(regionPageRows, rx, ry);
            String ocrSrc;
            if (cropRows.isEmpty()) {
                cropRows = ocrService.recognizeBufferedImage(cropImg, "apply");
                ocrSrc = "crop-ocr";
            } else {
                ocrSrc = "page-ocr-slice";
            }
            OcrUtils.dumpRawOcrRows(
                    "table-raw/" + ocrSrc + "/" + slot.getSlotLabel(),
                    cropImg.getWidth(), cropImg.getHeight(), cropRows);
            byte[] cropBytes = OcrUtils.toPngBytes(cropImg);
            if (cropBytes == null) {
                return null;
            }
            String slotDefLabel = TableLabelParser.buildFullTableLabel(slot);
            List<com.weavelay.core.layout.CellRect> tableCells;
            try {
                tableCells = com.weavelay.core.layout.TableBorderDetector.detectCells(
                        cropBytes, cropRows, slotDefLabel);
            } catch (Exception e) {
                tableCells = java.util.Collections.emptyList();
            }
            if (!tableCells.isEmpty()) {
                var tableFormula = recognizeFormulaColumnsAfterTablePairing(
                        cropImg, cropBytes, tableCells, cropRows, slot);
                tableCells = tableFormula.cells();
                cropRows = tableFormula.ocrRows();
            }
            String md = TablePopupManager.buildTableMarkdown(slotDefLabel, tableCells, cropRows);
            int[] offs = tableDataPhysOffsets(tableCells, slotDefLabel);
            ApplyTableCache cache = new ApplyTableCache(
                    slotDefLabel, tableCells, cropRows, rx, ry, rw, rh, offs[0], offs[1]);
            return new SlotRecognizeResult(
                    (md != null && !md.isBlank()) ? md : null, cache);
        }
        // 公式单字段
        java.awt.image.BufferedImage cropImg =
                OcrUtils.cropToBufferedImage(fullImg, rx, ry, rw, rh);
        if (cropImg == null) {
            return null;
        }
        java.util.List<com.weavelay.core.model.OcrRow> cropRows =
                OcrUtils.translateRowsToCrop(regionPageRows, rx, ry);
        if (cropRows.isEmpty()) {
            cropRows = ocrService.recognizeBufferedImage(cropImg, "apply");
        }
        String text = OcrUtils.ocrRowsToText(cropRows);
        try {
            String latex = layoutDetectAndRecognizeFormula(cropImg, slot.getSlotLabel());
            if (latex != null && !latex.isEmpty()) {
                text = latex;
            }
        } catch (Exception ignored) {
        }
        if (text != null && !text.isEmpty()) {
            return new SlotRecognizeResult(text, null);
        }
        return null;
    }

    /** 应用当前页面类型: 只裁图OCR已保存坐标的区域, 不做全页OCR. */
    private void applyCurrentPage() {
        WeavePageResult page = currentPageOrNull();
        if (page == null) {
            setStatus("请先加载PDF");
            return;
        }
        if (pageKindCombo == null || pageKindCombo.getValue() == null) {
            setStatus("请先选择页面类型");
            return;
        }
        String kindCode = pageKindCombo.getValue().getCode();
        String kindName = pageKindCombo.getValue().getDisplayName();
        // 更新页面类型
        sessionPages.set(currentPageIndex, page.withPageKind(kindCode, kindName));

        if (runtime == null) return;
        try {
            java.util.List<com.weavelay.core.page.SlotDefinition> slots =
                    runtime.getDatabase().loadSlots(kindCode);
            if (slots.isEmpty()) {
                setStatus("该页面类型无已保存规则");
                return;
            }
            applyBtn.setDisable(true);
            setStatus("提取中…");
            applyElapsedLabel.setText("");
            final int pageIdx = currentPageIndex;
            final byte[] png = page.getPagePng();
            final long startedAt = System.nanoTime();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                java.util.LinkedHashMap<String, String> filled = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, ApplyTableCache> tableCaches = new java.util.LinkedHashMap<>();
                try {
                    RapidOcrService.prepareNativeRuntime();
                    final java.awt.image.BufferedImage fullImg;
                    if (png != null && png.length > 0) {
                        fullImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                    } else {
                        fullImg = null;
                    }
                    // 整页 OCR 一次：普通字段取字 + 表格复用（避免材料表/目录表再各跑一遍）
                    java.util.List<com.weavelay.core.page.SlotDefinition> workSlots = new java.util.ArrayList<>();
                    for (com.weavelay.core.page.SlotDefinition slot : slots) {
                        if (fullImg == null) {
                            continue;
                        }
                        RectRegion override = getApplyRegionOverride(pageIdx, slot.getSlotCode());
                        boolean hasOverride = override != null
                                && override.getWidth() > 0 && override.getHeight() > 0;
                        if (slot.hasRegion() || hasOverride) {
                            workSlots.add(slot);
                        }
                    }
                    java.util.List<com.weavelay.core.model.OcrRow> pageRows = java.util.Collections.emptyList();
                    if (!workSlots.isEmpty() && fullImg != null) {
                        pageRows = ocrService.recognizeBufferedImage(fullImg, "apply");
                        OcrUtils.dumpRawOcrRows(
                                "page-apply", fullImg.getWidth(), fullImg.getHeight(), pageRows);
                    }
                    for (com.weavelay.core.page.SlotDefinition slot : workSlots) {
                        try {
                            int[] region = resolveApplyRegion(pageIdx, slot);
                            SlotRecognizeResult result = recognizeOneSlot(
                                    slot, fullImg, pageRows,
                                    region[0], region[1], region[2], region[3]);
                            if (result == null) {
                                continue;
                            }
                            if (result.text() != null && !result.text().isEmpty()) {
                                filled.put(slot.getSlotCode(), result.text());
                            }
                            if (result.tableCache() != null) {
                                tableCaches.put(slot.getSlotCode(), result.tableCache());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> {
                        applyBtn.setDisable(false);
                        setStatus("应用失败: " + ex.getMessage());
                    });
                    return;
                }
                final double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
                final int count = filled.size();
                javafx.application.Platform.runLater(() -> {
                    applyTableCacheByPage.put(pageIdx, tableCaches);
                    for (java.util.Map.Entry<String, String> e : filled.entrySet()) {
                        fillSlotValue(e.getKey(), e.getValue());
                    }
                    syncApplyTableColumnChildren();
                    if (outputTable != null) {
                        outputTable.refresh();
                    }
                    activateApplyTableVirtualCells();
                    applyBtn.setDisable(false);
                    setStatus("应用完成: " + count + " 个字段已填充。OCR原文: "
                            + OcrUtils.lastDumpPath().toAbsolutePath());
                    applyElapsedLabel.setText(String.format("耗时 %.2fs", seconds));
                });
            });
        } catch (Exception ex) {
            setStatus("应用失败: " + ex.getMessage());
            applyBtn.setDisable(false);
        }
    }

    /** 页面级快捷键: 翻页/选类型/扫描/应用. 返回 true 表示已处理. */
    private boolean handlePageShortcut(javafx.scene.input.KeyEvent e) {
        boolean typing = isTypingInEditableField(e);
        // 输入中：只放行特殊键盘快捷键，其余不抢
        if (typing) {
            if (e.getCode() == IndustrialSymbolPicker.HOTKEY
                    || (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.PERIOD)) {
                openSymbolPickerForFocus();
                return true;
            }
            return false;
        }
        if (e.getCode() == IndustrialSymbolPicker.HOTKEY
                || (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.PERIOD)) {
            openSymbolPickerForFocus();
            return true;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.SPACE && !e.isControlDown()) {
            // Space = 应用（仅非输入状态；输入空格绝不走这里；框选待重识时不抢）
            if (selectedKeyRow == null && pendingRowStart == 0 && pendingRowGroup == null
                    && pendingRegion == null
                    && workMode == AppPreferences.WorkMode.APPLY
                    && applyBtn != null && !applyBtn.isDisable() && applyBtn.isVisible()) {
                applyBtn.fire();
                return true;
            }
            return false;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.F2) {
            // F2 = 打开页面类型下拉列表, 用上下键选择, Enter 确认
            if (pageKindCombo != null && !pageKindCombo.isDisabled()) {
                pageKindCombo.requestFocus();
                pageKindCombo.show();
            }
            return true;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            // 应用模式：优先取消框选，再取消右侧字段选中 / 旁白
            if (workMode == AppPreferences.WorkMode.APPLY) {
                if (pendingRegion != null) {
                    pendingRegion = null;
                    weavePreview.clearPendingSelection();
                    weavePreview.setSlotLabels(null);
                    setStatus("已取消框选");
                    return true;
                }
                if (dismissOutputFocus(true)) {
                    return true;
                }
            }
            return false;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.LEFT || e.getCode() == javafx.scene.input.KeyCode.PAGE_UP) {
            if (currentPageIndex > 0) showPage(currentPageIndex - 1);
            return true;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.RIGHT || e.getCode() == javafx.scene.input.KeyCode.PAGE_DOWN) {
            if (currentPageIndex < sessionPages.size() - 1) showPage(currentPageIndex + 1);
            return true;
        }
        return false;
    }

    /** 焦点或事件目标在可编辑 TextField/TextArea 内（含其子节点）时视为正在输入。 */
    private boolean isTypingInEditableField(javafx.scene.input.KeyEvent e) {
        javafx.scene.Node n = null;
        if (e.getTarget() instanceof javafx.scene.Node node) {
            n = node;
        }
        if (n == null && primaryStage != null && primaryStage.getScene() != null) {
            n = primaryStage.getScene().getFocusOwner();
        }
        while (n != null) {
            if (n instanceof javafx.scene.control.TextInputControl tic) {
                return tic.isEditable() && !tic.isDisabled();
            }
            n = n.getParent();
        }
        return false;
    }

    /** 在当前聚焦的输入框光标处弹出特殊键盘。 */
    private void openSymbolPickerForFocus() {
        javafx.scene.Scene scene = primaryStage != null ? primaryStage.getScene() : null;
        if (IndustrialSymbolPicker.tryShowForFocus(scene, lastEditField)) {
            return;
        }
        setStatus("先点选要改的值，再按 " + IndustrialSymbolPicker.hotkeyLabel() + " 打开特殊键盘");
    }

    private void showPage(int index) {
        if (sessionPages.isEmpty()) {
            weavePreview.clear();
                outputRoot.getChildren().clear();
                updatePageNav();
            return;
        }
        // 保存当前页输出到缓存
        if (currentPageIndex >= 0 && currentPageIndex < sessionPages.size()) {
            outputCache.put(currentPageIndex, new java.util.ArrayList<>(outputRoot.getChildren()));
        }
        int safe = Math.max(0, Math.min(index, sessionPages.size() - 1));
        currentPageIndex = safe;
        pendingRegion = null;
        weavePreview.clearPendingSelection();
        weavePreview.setSlotLabels(null);
        WeavePageResult page = sessionPages.get(safe);
        weavePreview.showPage(page, safe + 1, sessionPages.size());
        // 恢复目标页缓存的输出，没有则按页型建空骨架
        outputRoot.getChildren().clear();
        java.util.List<javafx.scene.control.TreeItem<SlotValue>> cached = outputCache.get(safe);
        if (cached != null && !cached.isEmpty()) {
            outputRoot.getChildren().addAll(cached);
        }
        syncApplyTableColumnChildren();
        showRuleArea(false);
        refreshPageKindCombo(page);
        if (!page.getOcrRows().isEmpty()) {
            weavePreview.addOcrRows(page.getOcrRows());
        }
        refreshSlotsForCurrentPage(page);
        updatePageNav();
        if (workMode == AppPreferences.WorkMode.APPLY) {
            activateApplyTableVirtualCells();
        }
    }

    private void refreshOutputFromFamily() {
        if (runtime == null) return;
        outputRoot.getChildren().clear();
        WeavePageResult page = currentPageOrNull();
        if (page == null || page.getPageKindCode().equals(PageKind.UNKNOWN.getCode())) {
            outputRoot.getChildren().add(new javafx.scene.control.TreeItem<>(
                    new SlotValue("", "请先选择页面类型", "", null, null)));
            outputCache.remove(currentPageIndex);
            return;
        }
        try {
            PageKindDefinition def = runtime.getPageExtractService()
                    .getCatalog().getDefinitionByCode(page.getPageKindCode());
            List<SlotDefinition> slots = def != null ? def.getSlots() : java.util.Collections.emptyList();
            if (slots.isEmpty()) {
                outputRoot.getChildren().add(new javafx.scene.control.TreeItem<>(
                        new SlotValue("", "无已保存规则 — 画框→绑定→保存", "", null, null)));
                return;
            }
            applyTableHeaders.clear();
            for (SlotDefinition slot : slots) {
                String code = slot.getSlotCode();
                String label = slot.getSlotLabel();
                // 表格：label 以 * 开头 或 field_type=table
                if (slot.isTable() || (label != null && label.startsWith("*"))) {
                    TableLabelParser.ParsedTable pt = TableLabelParser.parseFromSlotDef(slot);
                    String tableName = pt.tableName();
                    String[] cols = pt.columns();
                    applyTableHeaders.put(code, cols);
                    javafx.scene.control.TreeItem<SlotValue> tp =
                            new javafx.scene.control.TreeItem<>(
                                    new SlotValue(code, tableName, "", null, null));
                    // 应用模式不展开列字段（值在父行 markdown / 弹窗里）；模板模式才列子节点方便绑列
                    if (workMode != AppPreferences.WorkMode.APPLY) {
                        tp.setExpanded(true);
                        String meta = slot.getFieldMeta();
                        if (meta != null && !meta.isEmpty()) {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper om =
                                        new com.fasterxml.jackson.databind.ObjectMapper();
                                com.fasterxml.jackson.databind.JsonNode jn = om.readTree(meta);
                                if (jn.has("columns")) {
                                    int cix = 0;
                                    for (com.fasterxml.jackson.databind.JsonNode c : jn.get("columns")) {
                                        tp.getChildren().add(new javafx.scene.control.TreeItem<>(
                                                new SlotValue(code + "_c" + cix, c.asText(), "", null, null)));
                                        cix++;
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        } else if (cols.length > 0) {
                            for (int ci = 0; ci < cols.length; ci++) {
                                tp.getChildren().add(new javafx.scene.control.TreeItem<>(
                                        new SlotValue(code + "_c" + ci, cols[ci].trim(), "", null, null)));
                            }
                        }
                    } else {
                        tp.setExpanded(false);
                    }
                    outputRoot.getChildren().add(tp);
                } else {
                    outputRoot.getChildren().add(new javafx.scene.control.TreeItem<>(
                            new SlotValue(code, label, "", null, null)));
                }
            }
        } catch (Exception ex) {
            outputRoot.getChildren().add(new javafx.scene.control.TreeItem<>(
                    new SlotValue("", "加载失败: " + ex.getMessage(), "", null, null)));
        }
        // 更新当前页缓存
        if (currentPageIndex >= 0) {
            outputCache.put(currentPageIndex, new java.util.ArrayList<>(outputRoot.getChildren()));
        }
    }

    private static String summarizeTableValueForList(String md) {
        if (md == null || md.isBlank()) {
            return "（未提取，点「应用」或点表名打开）";
        }
        if (!com.weavelay.app.export.ConfirmExcelWriter.looksLikeMarkdownTable(md)) {
            String one = md.replace('\n', ' ').trim();
            return one.length() > 60 ? one.substring(0, 59) + "…" : one;
        }
        java.util.List<java.util.List<String>> rows =
                com.weavelay.app.export.ConfirmExcelWriter.parseMarkdownTable(md);
        int dataRows = Math.max(0, rows.size() - 1);
        int filled = 0;
        for (int i = 1; i < rows.size(); i++) {
            for (String c : rows.get(i)) {
                if (c != null && !c.isBlank()) {
                    filled++;
                    break;
                }
            }
        }
        return "已提取 " + dataRows + " 行 / 非空 " + filled + " 行（点表名打开核对）";
    }

    private static String extractFieldName(String slotLabel) {
        if (slotLabel == null) return "";
        int space = slotLabel.indexOf(' ');
        return space > 0 ? slotLabel.substring(space + 1) : slotLabel;
    }

    private void refreshSlotsForCurrentPage(WeavePageResult page) {
        // 已有本页输出缓存则不要重建，避免冲掉已填 value
        boolean hasCachedOutput = hasFilledOrSkeletonOutput();
        if (page != null && page.getPageKindCode() != null
                && !page.getPageKindCode().equals(PageKind.UNKNOWN.getCode())
                && !hasCachedOutput) {
            refreshOutputFromFamily();
        }
        refreshPickerOptions(page);

        outputTable.getSelectionModel().clearSelection();
        weavePreview.clearHighlight();
        weavePreview.setVirtualCellRows(Collections.emptyList());
    }

    private boolean hasFilledOrSkeletonOutput() {
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) continue;
            String code = ti.getValue().getSlotCode();
            if (code != null && !code.isEmpty()) {
                return true;
            }
        }
        return false;
    }


    private void setupPickerBar() {
        pickerBar.setListener(new PageKindPickerBar.Listener() {
            @Override
            public void onDocumentFamilyChanged(FamilyRecord family) {
                if (runtime == null || family == null) return;
                if (family.getCode().equals(runtime.getFamilyCode())) return;
                try {
                    runtime.selectFamily(family.getCode());
                    refreshPickerOptions(null);
                    refreshOutputFromFamily();
                    refreshPageKindCombo(currentPageOrNull());
                    setStatus("已切换文件类型: " + family.getDisplayName());
                } catch (Exception ex) {
                    setStatus("切换文件类型失败:\n" + formatException(ex));
                }
            }
        });
        refreshPickerOptions(null);
    }

    private void refreshPickerOptions(WeavePageResult page) {
        if (runtime == null) return;
        try {
            List<FamilyRecord> families = runtime.listFamilies();
            FamilyRecord currentFamily = null;
            for (FamilyRecord family : families) {
                if (runtime.getFamilyCode().equals(family.getCode())) {
                    currentFamily = family;
                    break;
                }
            }
            pickerBar.setFamilies(families, currentFamily);
        } catch (Exception ex) {
            // ignore
        }
    }

    private void onSettingsSaved() {
        batchService.resetPageExtractService();
        refreshPickerFromSession();
        WeavePageResult page = currentPageOrNull();
        if (page == null) {
            return;
        }
        // 只刷新页面类型 / 输出骨架；不要 showPage 重载预览，否则缩放会被冲掉
        refreshPageKindCombo(page);
        refreshOutputFromFamily();
        outputTable.getSelectionModel().clearSelection();
        weavePreview.clearHighlight();
        weavePreview.setVirtualCellRows(java.util.Collections.emptyList());
        if (workMode == AppPreferences.WorkMode.APPLY) {
            activateApplyTableVirtualCells();
        }
    }

    private void refreshPickerFromSession() {
        WeavePageResult page = sessionPages.isEmpty()
                ? null
                : sessionPages.get(currentPageIndex);
        refreshPickerOptions(page);
    }

    private void updatePageNav() {
        int total = sessionPages.size();
        if (total == 0) {
            pageNavLabel.setText("0 / 0");
            prevPageBtn.setDisable(true);
            nextPageBtn.setDisable(true);
            return;
        }
        pageNavLabel.setText((currentPageIndex + 1) + " / " + total);
        prevPageBtn.setDisable(currentPageIndex <= 0);
        nextPageBtn.setDisable(currentPageIndex >= total - 1);
    }

    /** 确认：把本页输出按定义模板写入实际 JSON（含 link 回填），不再写 Excel。 */
    private void confirmCurrentPage() {
        if (!licenseService.allows(LicenseFeatures.CONFIRM)) {
            setStatus(licenseService.denyMessage(LicenseFeatures.CONFIRM));
            return;
        }
        if (workMode != AppPreferences.WorkMode.APPLY) {
            setStatus("请先切换到「应用」模式再确认");
            return;
        }
        WeavePageResult page = currentPageOrNull();
        if (page == null) {
            setStatus("请先加载PDF页面");
            return;
        }
        if (runtime == null) {
            setStatus("运行环境未初始化");
            return;
        }
        FamilyRecord family = pickerBar.getFamilyCombo().getValue();
        if (family == null || family.getCode() == null || family.getCode().isBlank()) {
            setStatus("请先选择文件类型");
            return;
        }
        try {
            confirmButton.setDisable(true);
            // 旁白/值列失焦提交可能晚于点击确认；先落盘再装袋
            if (weavePreview != null) {
                weavePreview.flushValueChipEdit();
            }
            if (lastEditField != null && lastEditField.getParent() != null) {
                // 触发值列 TextField 失焦提交（受上面「空不覆盖」保护）
                confirmButton.requestFocus();
            }
            String defJson = runtime.getDatabase().getFamilyOutputDefinitionJson(family.getCode());
            if (defJson == null || defJson.isBlank()) {
                setStatus("请先在设置中配置输出定义 JSON");
                return;
            }
            if (!outputJsonEngine.hasDocument()) {
                outputJsonEngine.reset(defJson);
            } else {
                outputJsonEngine.refreshDefinition(defJson);
            }
            com.weavelay.core.output.OutputJsonEngine.PageValueBag bag = buildPageValueBag();
            outputJsonEngine.applyPage(bag);
            refreshOutputJsonViewerIfOpen();
            int pageNo = currentPageIndex + 1;
            boolean hasNext = currentPageIndex < sessionPages.size() - 1;
            setStatus("第" + pageNo + "页已写入 JSON"
                    + (hasNext ? "  — 可翻下一页继续" : "  — 已是最后一页；点「当前JSON」查看"));
        } catch (Exception ex) {
            setStatus("写入 JSON 失败: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            confirmButton.setDisable(false);
        }
    }

    private com.weavelay.core.output.OutputJsonEngine.PageValueBag buildPageValueBag() {
        com.weavelay.core.output.OutputJsonEngine.PageValueBag bag =
                new com.weavelay.core.output.OutputJsonEngine.PageValueBag();
        java.util.Map<String, ApplyTableCache> pageCache = applyTableCacheByPage.get(currentPageIndex);
        java.util.Map<String, com.weavelay.core.page.SlotDefinition> slotByCode =
                new java.util.HashMap<>();
        try {
            WeavePageResult page = currentPageOrNull();
            if (page != null && runtime != null
                    && page.getPageKindCode() != null
                    && !page.getPageKindCode().isBlank()) {
                for (com.weavelay.core.page.SlotDefinition sd
                        : runtime.getDatabase().loadSlots(page.getPageKindCode())) {
                    if (sd != null && sd.getSlotCode() != null) {
                        slotByCode.put(sd.getSlotCode(), sd);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (javafx.scene.control.TreeItem<SlotValue> ti : outputRoot.getChildren()) {
            if (ti == null || ti.getValue() == null) {
                continue;
            }
            SlotValue sv = ti.getValue();
            String label = sv.getSlotLabel() == null ? "" : sv.getSlotLabel().trim();
            String code = sv.getSlotCode() == null ? "" : sv.getSlotCode().trim();
            String value = sv.getValue() == null ? "" : sv.getValue();
            // 旁白仍显示时，以旁白为准兜底（防止值列 TextField 把模型冲空）
            if (value.isBlank() && weavePreview != null) {
                String chipCode = weavePreview.getValueChipSlotCode();
                String chipText = weavePreview.getValueChipText();
                if (chipCode != null && chipCode.equals(code)
                        && chipText != null && !chipText.isBlank()) {
                    value = chipText;
                }
            }
            boolean table = isTableParentItem(ti)
                    || com.weavelay.app.export.ConfirmExcelWriter.looksLikeMarkdownTable(value)
                    || label.startsWith("*");
            // 已知标量字段名不要因文案里偶发 | 被当成表
            if (table && !isTableParentItem(ti) && !label.startsWith("*")
                    && ("技术要求".equals(label) || "工序号".equals(label) || "工序名称".equals(label)
                    || "同时加工件".equals(label))) {
                table = false;
            }
            if (table) {
                com.weavelay.core.page.SlotDefinition sd = slotByCode.get(code);
                TableLabelParser.ParsedTable pt = sd != null
                        ? TableLabelParser.parseFromSlotDef(sd)
                        : TableLabelParser.parse(label.startsWith("*") ? label : "*" + label);
                String tableName = pt.tableName();
                if (tableName == null || tableName.isBlank()) {
                    tableName = label.replace("*", "").trim();
                }
                String[] expectedCols = pt.columns();
                if ((expectedCols == null || expectedCols.length == 0)
                        && applyTableHeaders.containsKey(code)) {
                    expectedCols = applyTableHeaders.get(code);
                }
                java.util.List<java.util.Map<String, String>> rows =
                        rowsFromApplyCache(pageCache != null ? pageCache.get(code) : null, value);
                rows = alignTableRowsToExpectedColumns(rows, expectedCols);
                if (!tableName.isBlank() && rows != null && !rows.isEmpty()) {
                    putTableWithAliases(bag, tableName, rows);
                }
            } else if (!value.isBlank()) {
                if (!label.isBlank()) {
                    bag.putScalar(label, value);
                }
                if (!code.isBlank()) {
                    bag.putScalar(code, value);
                }
            }
        }
        return bag;
    }

    /**
     * 输出定义里常用「工序目录表」，槽位也可能叫「工序目录」——两边都放入袋，避免 confirm 对不上。
     */
    private static void putTableWithAliases(
            com.weavelay.core.output.OutputJsonEngine.PageValueBag bag,
            String tableName,
            java.util.List<java.util.Map<String, String>> rows) {
        if (bag == null || tableName == null || tableName.isBlank() || rows == null) {
            return;
        }
        bag.putTable(tableName, rows);
        if (tableName.endsWith("表") && tableName.length() > 1) {
            bag.putTable(tableName.substring(0, tableName.length() - 1), rows);
        } else {
            bag.putTable(tableName + "表", rows);
        }
    }

    /**
     * 表头与槽位列定义不一致时（如「名称」vs「设备.名称」），
     * 按列位置 + 少量别名对齐到定义期望列名，否则 OutputJsonEngine 填不出行。
     */
    private static java.util.List<java.util.Map<String, String>> alignTableRowsToExpectedColumns(
            java.util.List<java.util.Map<String, String>> rows,
            String[] expectedCols) {
        if (rows == null || rows.isEmpty()
                || expectedCols == null || expectedCols.length == 0) {
            return rows;
        }
        java.util.List<String> actualHeaders = new java.util.ArrayList<>(rows.get(0).keySet());
        boolean alreadyAligned = actualHeaders.size() == expectedCols.length;
        if (alreadyAligned) {
            for (int i = 0; i < expectedCols.length; i++) {
                if (!expectedCols[i].equals(actualHeaders.get(i))) {
                    alreadyAligned = false;
                    break;
                }
            }
        }
        if (alreadyAligned) {
            return rows;
        }
        java.util.List<java.util.Map<String, String>> out = new java.util.ArrayList<>();
        for (java.util.Map<String, String> row : rows) {
            if (row == null) {
                continue;
            }
            java.util.List<String> valuesInOrder = new java.util.ArrayList<>();
            for (String h : actualHeaders) {
                valuesInOrder.add(row.getOrDefault(h, ""));
            }
            java.util.Map<String, String> mapped = new java.util.LinkedHashMap<>();
            for (int i = 0; i < expectedCols.length; i++) {
                String expect = expectedCols[i];
                String v = row.get(expect);
                if (v == null || v.isBlank()) {
                    v = tableColumnAliasLookup(row, expect);
                }
                if ((v == null || v.isBlank()) && i < valuesInOrder.size()) {
                    v = valuesInOrder.get(i);
                }
                mapped.put(expect, v == null ? "" : v);
            }
            out.add(mapped);
        }
        return out;
    }

    private static String tableColumnAliasLookup(
            java.util.Map<String, String> row, String expect) {
        if (row == null || expect == null) {
            return null;
        }
        if (row.containsKey(expect)) {
            return row.get(expect);
        }
        // 定义用「设备.名称」，OCR/手改常写成「名称」「设备名称」
        if (expect.contains(".")) {
            String leaf = expect.substring(expect.lastIndexOf('.') + 1);
            if (row.containsKey(leaf) && row.get(leaf) != null && !row.get(leaf).isBlank()) {
                return row.get(leaf);
            }
            String compact = expect.replace(".", "");
            if (row.containsKey(compact) && row.get(compact) != null && !row.get(compact).isBlank()) {
                return row.get(compact);
            }
        }
        if ("特性代号".equals(expect)) {
            for (String alt : new String[] {"材料代号", "代号", "特性"}) {
                if (row.containsKey(alt) && row.get(alt) != null && !row.get(alt).isBlank()) {
                    return row.get(alt);
                }
            }
        }
        return null;
    }

    /**
     * 确认装袋：右侧槽位值优先（含手改后的 Markdown 表）。
     * 仅当槽位没有可用表内容时，才回退「应用」OCR 格子缓存。
     */
    private static java.util.List<java.util.Map<String, String>> rowsFromApplyCache(
            ApplyTableCache cached, String slotMarkdown) {
        // 槽位值已是最终展示文本（OCR 写入前已 convert），确认时不再二次加工
        String slotMdPlain = null;
        if (slotMarkdown != null && !slotMarkdown.isBlank()) {
            slotMdPlain = slotMarkdown;
        }
        // 手改后的完整表在槽位里：必须优先生效，否则会被旧 OCR 缓存盖掉
        if (slotMdPlain != null
                && com.weavelay.app.export.ConfirmExcelWriter.looksLikeMarkdownTable(slotMdPlain)) {
            java.util.List<java.util.Map<String, String>> fromSlot = markdownTableToRows(slotMdPlain);
            if (fromSlot != null && !fromSlot.isEmpty()) {
                return fromSlot;
            }
        }
        String md = null;
        if (cached != null && cached.cells() != null && !cached.cells().isEmpty()) {
            String structMd = TablePopupManager.buildTableMarkdown(
                    cached.slotDefLabel(), cached.cells(), cached.ocrRows());
            if (structMd != null && !structMd.isBlank()) {
                md = TablePopupManager.mergeMarkdownPreserveStructure(structMd, slotMdPlain);
            }
        }
        if (md == null || md.isBlank()) {
            md = slotMdPlain;
        }
        return markdownTableToRows(md);
    }

    private static java.util.List<java.util.Map<String, String>> markdownTableToRows(String md) {
        java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        java.util.List<java.util.List<String>> table =
                com.weavelay.app.export.ConfirmExcelWriter.parseMarkdownTable(md);
        if (table == null || table.size() < 2) {
            return rows;
        }
        java.util.List<String> header = table.get(0);
        for (int r = 1; r < table.size(); r++) {
            java.util.List<String> line = table.get(r);
            java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String h = header.get(c) == null ? "" : header.get(c).trim();
                if (h.isEmpty()) {
                    continue;
                }
                String cell = c < line.size() && line.get(c) != null ? line.get(c).trim() : "";
                map.put(h, cell);
            }
            if (!map.isEmpty() && !isAllBlank(map)) {
                rows.add(map);
            }
        }
        return rows;
    }

    private static boolean isAllBlank(java.util.Map<String, String> map) {
        for (String v : map.values()) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void showOutputJsonViewer() {
        if (outputJsonStage == null) {
            outputJsonViewer = new com.weavelay.app.ui.JsonViewerWebPane();
            Button refresh = new Button("刷新");
            refresh.getStyleClass().add("app-btn");
            refresh.setOnAction(e -> refreshOutputJsonViewerIfOpen());
            Button copy = new Button("复制");
            copy.getStyleClass().add("app-btn");
            copy.setOnAction(e -> {
                String t = outputJsonEngine.hasDocument()
                        ? outputJsonEngine.toPrettyJson()
                        : "";
                if (t != null && !t.isEmpty()) {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(t);
                    Clipboard.getSystemClipboard().setContent(content);
                    setStatus("已复制当前 JSON");
                }
            });
            Label tip = new Label("可折叠查看；弹窗是 OCR 原样，这里是确认后整理过的结果");
            tip.getStyleClass().add("app-label-muted");
            HBox bar = new HBox(8, refresh, copy, tip);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(8));
            javafx.scene.layout.BorderPane root = new javafx.scene.layout.BorderPane();
            root.setTop(bar);
            root.setCenter(outputJsonViewer);
            root.setStyle("-fx-background-color: #f3f8f8;");
            outputJsonStage = new javafx.stage.Stage();
            outputJsonStage.setTitle("当前输出 JSON");
            outputJsonStage.initOwner(primaryStage);
            Scene scene = new Scene(root, 860, 700);
            var appCss = getClass().getResource("ui/app.css");
            if (appCss != null) {
                scene.getStylesheets().add(appCss.toExternalForm());
            }
            outputJsonStage.setScene(scene);
        }
        refreshOutputJsonViewerIfOpen();
        if (!outputJsonStage.isShowing()) {
            outputJsonStage.show();
        } else {
            outputJsonStage.toFront();
        }
    }

    private void refreshOutputJsonViewerIfOpen() {
        if (outputJsonViewer == null) {
            return;
        }
        String text = outputJsonEngine.hasDocument()
                ? outputJsonEngine.toPrettyJson()
                : "（尚未确认任何页，或已换文件清空）";
        outputJsonViewer.setText(text);
    }

    /**
     * 应用模式：把累计输出 JSON 按文件类型 Excel 模板写出一份 xlsx。
     */
    private void exportAccumulatedJsonToExcel() {
        if (!licenseService.allows(LicenseFeatures.EXCEL_EXPORT)) {
            setStatus(licenseService.denyMessage(LicenseFeatures.EXCEL_EXPORT));
            return;
        }
        if (workMode != AppPreferences.WorkMode.APPLY) {
            setStatus("请先切换到「应用」模式再写出 Excel");
            return;
        }
        if (!outputJsonEngine.hasDocument()) {
            setStatus("尚未确认任何页，没有可写出的 JSON");
            return;
        }
        if (selectedPath == null || !Files.isRegularFile(selectedPath)) {
            setStatus("请先选择 PDF 文件");
            return;
        }
        if (runtime == null) {
            setStatus("运行环境未初始化");
            return;
        }
        FamilyRecord family = pickerBar.getFamilyCombo().getValue();
        if (family == null || family.getCode() == null || family.getCode().isBlank()) {
            setStatus("请先选择文件类型");
            return;
        }
        String tplRaw = family.getExcelTemplatePath();
        if (tplRaw == null || tplRaw.isBlank()) {
            setStatus("请先在设置里为该文件类型绑定 Excel 输出模板");
            return;
        }
        Path templatePath = Path.of(tplRaw);
        if (!Files.isRegularFile(templatePath)) {
            setStatus("Excel 模板不存在: " + templatePath);
            return;
        }
        Path exportDir = preferences.getExportDir();
        if (exportDir == null || !Files.isDirectory(exportDir)) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择 Excel 导出目录");
            java.io.File dir = chooser.showDialog(primaryStage);
            if (dir == null) {
                return;
            }
            exportDir = dir.toPath();
            preferences.setExportDir(exportDir);
        }
        String familyName = family.getName();
        if (familyName == null || familyName.isBlank()) {
            familyName = family.getCode();
        }
        String pdfBaseName = selectedPath.getFileName().toString();
        if (pdfBaseName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            pdfBaseName = pdfBaseName.substring(0, pdfBaseName.length() - 4);
        }
        Path outputPath = exportDir.resolve(familyName + "-" + pdfBaseName + ".xlsx");
        if (Files.isRegularFile(outputPath)) {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION);
            alert.initOwner(primaryStage);
            alert.setTitle("覆盖 Excel");
            alert.setHeaderText(null);
            alert.setContentText("已存在文件：\n" + outputPath + "\n\n覆盖后从模板重新填充，是否继续？");
            java.util.Optional<javafx.scene.control.ButtonType> ans = alert.showAndWait();
            if (ans.isEmpty() || ans.get() != javafx.scene.control.ButtonType.OK) {
                return;
            }
            try {
                Files.deleteIfExists(outputPath);
            } catch (Exception ex) {
                setStatus("无法删除旧文件: " + ex.getMessage());
                return;
            }
        }
        try {
            exportExcelBtn.setDisable(true);
            var maps = com.weavelay.app.export.JsonDocumentToSlotMaps.fromDocument(
                    outputJsonEngine.document());
            String defJson = runtime.getDatabase().getFamilyOutputDefinitionJson(family.getCode());
            java.util.List<com.weavelay.core.store.TemplateRule> rules =
                    com.weavelay.app.export.DefinitionRouteRules.parse(defJson);
            com.weavelay.app.export.TemplateExcelFiller.ensureOutputFromTemplate(
                    templatePath, outputPath);
            com.weavelay.app.export.TemplateExcelFiller.fill(
                    outputPath, templatePath, maps, rules);
            setStatus("已写出 Excel: " + outputPath.toAbsolutePath());
        } catch (Exception ex) {
            setStatus("写出 Excel 失败: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            exportExcelBtn.setDisable(false);
        }
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null ? "" : text);
    }

    private void copyStatusToClipboard() {
        String text = statusLabel.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static String formatException(Throwable ex) {
        if (ex == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.flush();
        return sw.toString().trim();
    }

    /**
     * 整表物理格配对后：对 fieldMeta.formulaColumns 中的列逐格做版式检测 + 公式识别，
     * 并用 LaTeX 回写 CellRect / OCR，供弹窗展示。
     */
    private TableFormulaVerifyResult recognizeFormulaColumnsAfterTablePairing(
            byte[] tableCropBytes,
            List<CellRect> cells,
            List<OcrRow> ocrRows,
            SlotDefinition slotDef) {
        java.awt.image.BufferedImage tableImg = null;
        if (tableCropBytes != null && tableCropBytes.length > 0) {
            try {
                tableImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(tableCropBytes));
            } catch (Exception ignored) {
            }
        }
        return recognizeFormulaColumnsAfterTablePairing(
                tableImg, tableCropBytes, cells, ocrRows, slotDef);
    }

    private TableFormulaVerifyResult recognizeFormulaColumnsAfterTablePairing(
            java.awt.image.BufferedImage tableImg,
            byte[] tableCropBytes,
            List<CellRect> cells,
            List<OcrRow> ocrRows,
            SlotDefinition slotDef) {
        TableLabelParser.ParsedTable pt = TableLabelParser.parseFromSlotDef(slotDef);
        String[] columns = pt.columns();
        if (columns.length == 0 || cells == null || cells.isEmpty()) {
            return new TableFormulaVerifyResult(cells, ocrRows, 0);
        }

        Set<Integer> formulaColIdx = new HashSet<>();
        Set<String> headerSet = new HashSet<>();
        for (int i = 0; i < columns.length; i++) {
            String name = columns[i] == null ? "" : columns[i].trim();
            if (name.startsWith(SlotDefinition.FORMULA_PREFIX)) {
                name = name.substring(SlotDefinition.FORMULA_PREFIX.length());
            }
            if (!name.isEmpty()) {
                headerSet.add(name);
            }
            if (slotDef.isFormulaColumn(name)) {
                formulaColIdx.add(i);
            }
        }
        if (formulaColIdx.isEmpty()) {
            return new TableFormulaVerifyResult(cells, ocrRows, 0);
        }

        Set<String> headerSetForSkip = new HashSet<>(headerSet);
        List<CellRect> dataCells = new ArrayList<>();
        for (CellRect cell : cells) {
            String t = cell.getText() != null ? cell.getText().trim() : "";
            if (cell.getRowspan() == 1 && !t.isEmpty() && headerSetForSkip.contains(t)) {
                continue;
            }
            dataCells.add(cell);
        }
        if (dataCells.isEmpty()) {
            return new TableFormulaVerifyResult(cells, ocrRows, 0);
        }

        int cellColMin = dataCells.stream().mapToInt(CellRect::getCol).min().orElse(0);
        int cellColMax = dataCells.stream().mapToInt(CellRect::getCol).max().orElse(0);
        int cellRowMin = dataCells.stream().mapToInt(CellRect::getRow).min().orElse(0);
        int cellRowMax = dataCells.stream().mapToInt(CellRect::getRow).max().orElse(0);
        java.util.List<CellRect> coreForMargin = new java.util.ArrayList<>();
        for (CellRect cell : dataCells) {
            int cr = cell.getRow();
            int cc = cell.getCol();
            String t = cell.getText() != null ? cell.getText().trim() : "";
            if ((cr == cellRowMin || cr == cellRowMax || cc == cellColMin || cc == cellColMax)
                    && t.isEmpty()) {
                continue;
            }
            coreForMargin.add(cell);
        }
        int[] marginOff = TablePopupManager.resolveMarginOffsets(dataCells, coreForMargin);
        boolean hasLeftMargin = marginOff[1] == 1;
        boolean hasTopMargin = marginOff[0] == 1;


        Map<String, String> latexByCellKey = new LinkedHashMap<>();
        List<CellFormulaPending> pendingCells = new ArrayList<>();
        for (CellRect cell : cells) {
            String t = cell.getText() != null ? cell.getText().trim() : "";
            if (cell.getRowspan() == 1 && !t.isEmpty() && headerSetForSkip.contains(t)) {
                continue;
            }
            int logicalCol = cell.getCol() - (hasLeftMargin ? 1 : 0);
            if (!formulaColIdx.contains(logicalCol)) {
                continue;
            }
            int cr = cell.getRow();
            int cc = cell.getCol();
            if ((cr == cellRowMin || cr == cellRowMax || cc == cellColMin || cc == cellColMax)
                    && t.isEmpty()) {
                continue;
            }
            if (hasTopMargin && cr == cellRowMin) {
                continue;
            }

            String cellKey = cr + "," + cc;
            try {
                int cx = (int) Math.round(cell.getX());
                int cy = (int) Math.round(cell.getY());
                int cw = Math.max(1, (int) Math.round(cell.getWidth()));
                int ch = Math.max(1, (int) Math.round(cell.getHeight()));
                BufferedImage cellImg = tableImg != null
                        ? OcrUtils.cropToBufferedImage(tableImg, cx, cy, cw, ch)
                        : null;
                byte[] cellBytes = null;
                if (cellImg != null) {
                    cellBytes = OcrUtils.toPngBytes(cellImg);
                } else {
                    cellBytes = OcrUtils.cropPagePng(tableCropBytes, cx, cy, cw, ch);
                    if (cellBytes != null) {
                        try {
                            cellImg = javax.imageio.ImageIO.read(
                                    new java.io.ByteArrayInputStream(cellBytes));
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (cellBytes == null || cellBytes.length == 0) {
                    continue;
                }
                List<FormulaSubJob> jobs = collectFormulaSubJobs(cellBytes, cellImg, cellKey);
                if (!jobs.isEmpty()) {
                    pendingCells.add(new CellFormulaPending(cell, t, cellImg, cellBytes, jobs));
                }
            } catch (Exception ignored) {
            }
        }

        if (!pendingCells.isEmpty()) {
            List<FormulaSubJob> allJobs = new ArrayList<>();
            for (CellFormulaPending p : pendingCells) {
                allJobs.addAll(p.jobs());
            }
            List<String> apiLatex = recognizeFormulaCropsParallel(allJobs);
            int jobIdx = 0;
            for (CellFormulaPending p : pendingCells) {
                List<com.weavelay.ocr.FormulaMixedComposer.FormulaPiece> pieces = new ArrayList<>();
                for (FormulaSubJob job : p.jobs()) {
                    String subLatex = jobIdx < apiLatex.size() ? apiLatex.get(jobIdx) : "";
                    jobIdx++;
                    if (job.subImg() != null) {
                        subLatex = com.weavelay.ocr.FormulaDigitSalvage.salvageIfNoDigit(
                                subLatex, job.subImg(), ocrService);
                    } else {
                        subLatex = com.weavelay.ocr.FormulaDigitSalvage.salvageIfNoDigit(
                                subLatex, job.subCrop(), ocrService);
                    }
                    if (subLatex == null || subLatex.isEmpty()) {
                        continue;
                    }
                    pieces.add(new com.weavelay.ocr.FormulaMixedComposer.FormulaPiece(
                            job.x(), job.y(), job.w(), job.h(), subLatex, job.display()));
                }
                if (pieces.isEmpty()) {
                    continue;
                }
                String mixed = com.weavelay.ocr.FormulaMixedComposer.composePreferringPlain(
                        p.cellImg(), p.cellBytes(), pieces, p.plainText(), ocrService);
                if (mixed != null && !mixed.isEmpty()) {
                    latexByCellKey.put(
                            p.cell().getRow() + "," + p.cell().getCol(), mixed);
                }
            }
        }

        List<CellRect> updatedCells = new ArrayList<>(cells.size());
        for (CellRect cell : cells) {
            String key = cell.getRow() + "," + cell.getCol();
            String latex = latexByCellKey.get(key);
            if (latex != null && !latex.isEmpty()) {
                updatedCells.add(new CellRect(
                        cell.getX(), cell.getY(), cell.getWidth(), cell.getHeight(),
                        cell.getRow(), cell.getCol(), cell.getRowspan(), cell.getColspan(),
                        latex));
            } else {
                updatedCells.add(cell);
            }
        }

        if (latexByCellKey.isEmpty()) {
            return new TableFormulaVerifyResult(updatedCells, ocrRows, 0);
        }

        List<OcrRow> patchedOcr = patchOcrWithFormulaCells(ocrRows, updatedCells, latexByCellKey);
        return new TableFormulaVerifyResult(updatedCells, patchedOcr, latexByCellKey.size());
    }

    /** 把公式格的 LaTeX 写回 OCR：去掉落入该格的碎片，换成一条合成行，弹窗配对才能显示。 */
    private static List<OcrRow> patchOcrWithFormulaCells(
            List<OcrRow> ocrRows,
            List<CellRect> cells,
            Map<String, String> latexByCellKey) {
        List<CellRect> formulaCells = new ArrayList<>();
        for (CellRect cell : cells) {
            if (latexByCellKey.containsKey(cell.getRow() + "," + cell.getCol())) {
                formulaCells.add(cell);
            }
        }
        if (formulaCells.isEmpty()) {
            return ocrRows;
        }

        List<OcrRow> kept = new ArrayList<>();
        for (OcrRow ocr : ocrRows) {
            String text = ocr.getFeature() != null ? ocr.getFeature().trim() : "";
            if (text.isEmpty()) {
                continue;
            }
            boolean overlapsFormula = false;
            for (CellRect cell : formulaCells) {
                double ox = Math.max(ocr.getStartX(), cell.getX());
                double oy = Math.max(ocr.getStartY(), cell.getY());
                double ex = Math.min(ocr.getEndX(), cell.getEndX());
                double ey = Math.min(ocr.getEndY(), cell.getEndY());
                if (ex > ox && ey > oy) {
                    overlapsFormula = true;
                    break;
                }
            }
            if (!overlapsFormula) {
                kept.add(ocr);
            }
        }
        for (CellRect cell : formulaCells) {
            String latex = latexByCellKey.get(cell.getRow() + "," + cell.getCol());
            if (latex == null || latex.isEmpty()) {
                continue;
            }
            String[] lines = latex.split("\\R");
            double h = Math.max(1.0, cell.getHeight());
            double lineH = h / Math.max(1, lines.length);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i] == null ? "" : lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                double y0 = cell.getY() + i * lineH;
                double y1 = cell.getY() + (i + 1) * lineH;
                kept.add(new OcrRow(
                        "formula",
                        line,
                        1.0,
                        cell.getX(),
                        y0,
                        cell.getEndX(),
                        y1,
                        null));
            }
        }
        return kept;
    }

    /** 与本地公式引擎池大小对齐；假并行时再高也没用。 */
    private static final int FORMULA_API_PARALLELISM =
            com.weavelay.ocr.formula.FormulaOnnxBootstrap.POOL_SIZE;

    private record FormulaSubJob(
            String cellKey,
            int x,
            int y,
            int w,
            int h,
            boolean display,
            BufferedImage subImg,
            byte[] subCrop) {}

    private record CellFormulaPending(
            CellRect cell,
            String plainText,
            BufferedImage cellImg,
            byte[] cellBytes,
            List<FormulaSubJob> jobs) {}

    /**
     * 版式检测 + 扩框裁剪（不调公式 API）。ORT 非线程安全，须串行。
     */
    private List<FormulaSubJob> collectFormulaSubJobs(
            byte[] cropBytes, BufferedImage cellImg, String cellKey) {
        List<FormulaSubJob> jobs = new ArrayList<>();
        if (cropBytes == null || cropBytes.length == 0) {
            return jobs;
        }
        if (cellImg == null) {
            try {
                cellImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cropBytes));
            } catch (Exception ignored) {
            }
        }
        var layoutSvc = batchService.getLayoutService();
        int imgW = cellImg != null ? cellImg.getWidth() : 0;
        int imgH = cellImg != null ? cellImg.getHeight() : 0;

        List<PpDocLayoutBox> allBoxes = new ArrayList<>();
        if (layoutSvc != null) {
            float thr = Math.min(
                    layoutSvc.getDefaultScoreThreshold(),
                    com.weavelay.ocr.layout.FormulaBoxSelector.CELL_FORMULA_SCORE_MIN);
            try {
                var layout = layoutSvc.detect(cropBytes, thr, false);
                if (layout != null && layout.getBoxes() != null) {
                    allBoxes.addAll(layout.getBoxes());
                }
            } catch (Exception ignored) {
            }
        }

        List<PpDocLayoutBox> formulaBoxes =
                com.weavelay.ocr.layout.FormulaBoxSelector.select(allBoxes, imgW, imgH);
        for (PpDocLayoutBox box : formulaBoxes) {
            try {
                int x = Math.max(0, (int) box.getXmin());
                int y = Math.max(0, (int) box.getYmin());
                int w = Math.max(1, (int) box.getWidth());
                int h = Math.max(1, (int) box.getHeight());
                if (cellImg != null) {
                    var ink = com.weavelay.ocr.FormulaInkBounds.expandForDiameter(cellImg, x, y, w, h);
                    x = ink.x();
                    y = ink.y();
                    w = ink.w();
                    h = ink.h();
                }
                BufferedImage subImg = cellImg != null
                        ? OcrUtils.cropToBufferedImage(cellImg, x, y, w, h)
                        : null;
                byte[] subCrop = subImg != null
                        ? OcrUtils.toPngBytes(subImg)
                        : OcrUtils.cropPagePng(cropBytes, x, y, w, h);
                if (subCrop == null) {
                    continue;
                }
                jobs.add(new FormulaSubJob(
                        cellKey, x, y, w, h,
                        "display_formula".equals(box.getLabelName()),
                        subImg, subCrop));
            } catch (Exception ignored) {
            }
        }
        return jobs;
    }

    /** 并行调公式 API（默认最多 4 路）；结果顺序与 jobs 一致。 */
    private List<String> recognizeFormulaCropsParallel(List<FormulaSubJob> jobs) {
        List<String> empty = new ArrayList<>();
        if (jobs == null || jobs.isEmpty()) {
            return empty;
        }
        if (jobs.size() == 1) {
            try {
                String latex = formulaService.recognizeSingle(jobs.get(0).subCrop());
                empty.add(latex != null ? latex : "");
            } catch (Exception e) {
                empty.add("");
            }
            return empty;
        }
        int poolSize = Math.min(
                Math.max(1, com.weavelay.ocr.formula.FormulaOnnxBootstrap.poolSize()),
                jobs.size());
        poolSize = Math.min(FORMULA_API_PARALLELISM, poolSize);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(poolSize);
        try {
            List<java.util.concurrent.CompletableFuture<String>> futures = new ArrayList<>(jobs.size());
            for (FormulaSubJob job : jobs) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        String latex = formulaService.recognizeSingle(job.subCrop());
                        return latex != null ? latex : "";
                    } catch (Exception e) {
                        return "";
                    }
                }, pool));
            }
            List<String> out = new ArrayList<>(jobs.size());
            for (java.util.concurrent.CompletableFuture<String> f : futures) {
                out.add(f.join());
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 版式检测 + 公式识别：仅当检出带有效 bbox 的公式子区域时才裁剪送 API；
     * 无公式坐标则跳过，不整图兜底。
     * @return LaTeX，失败或跳过返回空串
     */
    private String layoutDetectAndRecognizeFormula(byte[] cropBytes, String logTag) {
        if (cropBytes == null || cropBytes.length == 0) {
            return "";
        }
        BufferedImage cellImg = null;
        try {
            cellImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cropBytes));
        } catch (Exception ignored) {
        }
        return layoutDetectAndRecognizeFormula(cropBytes, cellImg, logTag);
    }

    private String layoutDetectAndRecognizeFormula(BufferedImage cellImg, String logTag) {
        if (cellImg == null) {
            return "";
        }
        byte[] cropBytes;
        try {
            cropBytes = OcrUtils.toPngBytes(cellImg);
        } catch (Exception e) {
            return "";
        }
        return layoutDetectAndRecognizeFormula(cropBytes, cellImg, logTag);
    }

    private String layoutDetectAndRecognizeFormula(
            byte[] cropBytes, BufferedImage cellImg, String logTag) {
        List<FormulaSubJob> jobs = collectFormulaSubJobs(cropBytes, cellImg, logTag);
        if (jobs.isEmpty()) {
            return "";
        }
        List<String> apiLatex = recognizeFormulaCropsParallel(jobs);
        List<com.weavelay.ocr.FormulaMixedComposer.FormulaPiece> pieces = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            FormulaSubJob job = jobs.get(i);
            String subLatex = i < apiLatex.size() ? apiLatex.get(i) : "";
            if (job.subImg() != null) {
                subLatex = com.weavelay.ocr.FormulaDigitSalvage.salvageIfNoDigit(
                        subLatex, job.subImg(), ocrService);
            } else {
                subLatex = com.weavelay.ocr.FormulaDigitSalvage.salvageIfNoDigit(
                        subLatex, job.subCrop(), ocrService);
            }
            if (subLatex == null || subLatex.isEmpty()) {
                continue;
            }
            pieces.add(new com.weavelay.ocr.FormulaMixedComposer.FormulaPiece(
                    job.x(), job.y(), job.w(), job.h(), subLatex, job.display()));
        }
        if (pieces.isEmpty()) {
            return "";
        }
        if (cellImg == null && cropBytes != null) {
            try {
                cellImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cropBytes));
            } catch (Exception ignored) {
            }
        }
        String mixed = com.weavelay.ocr.FormulaMixedComposer.composePreferringPlain(
                cellImg, cropBytes, pieces, "", ocrService);
        return mixed != null ? mixed : "";
    }

    private record TableFormulaVerifyResult(List<CellRect> cells, List<OcrRow> ocrRows, int hitCount) {}

}
