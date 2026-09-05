package com.weavelay.app.ui;

import com.weavelay.app.AppPreferences;
import com.weavelay.core.license.LicenseFeatures;
import com.weavelay.core.license.LicenseService;
import com.weavelay.core.pack.FamilyTemplatePackException;
import com.weavelay.core.pack.FamilyTemplatePackResult;
import com.weavelay.core.pack.FamilyTemplatePackService;
import com.weavelay.core.page.SlotDefinition;
import com.weavelay.core.page.SlotDefinitionFormat;
import com.weavelay.core.store.FamilyRecord;
import com.weavelay.core.store.PageKindRecord;
import com.weavelay.core.store.WeaveLayRuntime;
import com.weavelay.ocr.RapidOcrService;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TableCell;
import javafx.scene.layout.Region;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * 可拖动的非模态设置窗口: 左侧菜单 + 右侧内容.
 */
public final class SettingsWindow {

    private static SettingsWindow active;

    private final Stage stage;
    private final WeaveLayRuntime runtime;
    private final LicenseService licenseService;
    private final Runnable onSaved;

    private final BorderPane contentHost = new BorderPane();
    private final FamilyManagePane familyPane;
    private final PageKindManagePane pageKindPane;
    private final OcrSettingsPane ocrSettingsPane;
    private final ExportSettingsPane exportSettingsPane;
    private final LicenseSettingsPane licensePane;
    private final FamilyOutputPane outputPane;

    private SettingsWindow(
            Window owner,
            WeaveLayRuntime runtime,
            AppPreferences preferences,
            RapidOcrService ocrService,
            LicenseService licenseService,
            Runnable onSaved) {
        this.runtime = runtime;
        this.licenseService = licenseService == null ? new LicenseService() : licenseService;
        this.onSaved = onSaved;
        this.familyPane = new FamilyManagePane(this);
        this.pageKindPane = new PageKindManagePane(this);
        this.outputPane = new FamilyOutputPane(this);

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("设置");
        stage.setMinWidth(720);
        stage.setMinHeight(480);

        this.ocrSettingsPane = new OcrSettingsPane(
                preferences, ocrService, stage, this::notifySaved);
        this.exportSettingsPane = new ExportSettingsPane(preferences, stage);
        this.licensePane = new LicenseSettingsPane(this.licenseService, stage, this::notifySaved);

        ListView<String> nav = new ListView<String>();
        nav.getItems().addAll("文件类型", "OCR 参数", "导出", "Licence");
        nav.getSelectionModel().select(0);
        nav.setPrefWidth(132);
        nav.setMinWidth(132);
        nav.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, item) -> {
            if ("文件类型".equals(item)) {
                showFamilyManage();
            } else if ("OCR 参数".equals(item)) {
                showOcrSettings();
            } else if ("导出".equals(item)) {
                showExportSettings();
            } else if ("Licence".equals(item)) {
                showLicenseSettings();
            }
        });

        nav.getStyleClass().add("settings-nav");
        nav.setStyle("-fx-background-color: #eef5f5; -fx-border-color: #c5dede; -fx-border-width: 0 1 0 0;");

        Label hint = new Label("提示: 可拖动本窗口至一侧，对照主界面 PDF 与 OCR 诊断配置模板。");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);
        hint.setPadding(new Insets(8, 12, 8, 12));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("settings-root");
        root.setLeft(nav);
        root.setCenter(contentHost);
        root.setBottom(hint);
        BorderPane.setMargin(nav, new Insets(0));

        Scene scene = new Scene(root, 860, 520);
        scene.getStylesheets().add(
                SettingsWindow.class.getResource("settings.css").toExternalForm());
        stage.setScene(scene);
        showFamilyManage();
    }

    public static void open(
            Window owner,
            WeaveLayRuntime runtime,
            AppPreferences preferences,
            RapidOcrService ocrService,
            LicenseService licenseService,
            Runnable onSaved) {
        if (active != null && active.stage.isShowing()) {
            active.stage.toFront();
            active.stage.requestFocus();
            return;
        }
        active = new SettingsWindow(owner, runtime, preferences, ocrService, licenseService, onSaved);
        active.stage.setOnHidden(e -> active = null);
        active.stage.show();
    }

    WeaveLayRuntime getRuntime() {
        return runtime;
    }

    LicenseService getLicenseService() {
        return licenseService;
    }

    void notifySaved() {
        if (onSaved != null) {
            onSaved.run();
        }
    }

    void showFamilyManage() {
        familyPane.showList();
        contentHost.setCenter(familyPane.getRoot());
    }

    void showOcrSettings() {
        contentHost.setCenter(ocrSettingsPane.getRoot());
    }

    void showExportSettings() {
        contentHost.setCenter(exportSettingsPane.getRoot());
    }

    void showLicenseSettings() {
        licensePane.reload();
        contentHost.setCenter(licensePane.getRoot());
    }

    void showOutputSettings() {
        outputPane.reloadFamilies();
        contentHost.setCenter(outputPane.getRoot());
    }

    void showFamilyOutput(FamilyRecord family) {
        outputPane.editFamily(family);
        contentHost.setCenter(outputPane.getRoot());
    }

    void showPageKindManage(FamilyRecord family) {
        pageKindPane.load(family);
        contentHost.setCenter(pageKindPane.getRoot());
    }

    public Window getWindow() {
        return stage;
    }

    static void showError(Window owner, String title, Exception ex) {
        SettingsDialogs.showError(owner, title, ex);
    }

    static void showInfo(Window owner, String message) {
        SettingsDialogs.showInfo(owner, message);
    }

    /**
     * 文件类型列表: 名称 / 英文 / 描述 + 行内操作.
     */
    private final class FamilyManagePane {

        private final BorderPane root = new BorderPane();
        private final VBox listPanel = new VBox(10);
        private final FamilyAddFormPane addFormPane;
        private final TableView<FamilyRecord> table = new TableView<FamilyRecord>();
        private final ObservableList<FamilyRecord> items = FXCollections.observableArrayList();
        private final SettingsWindow window;

        private FamilyManagePane(SettingsWindow window) {
            this.window = window;
            this.addFormPane = new FamilyAddFormPane(this::showList, this::saveNewFamily);
            root.setPadding(new Insets(0));
            buildListPanel();
            showList();
        }

        private void buildListPanel() {
            listPanel.setPadding(new Insets(12, 16, 12, 12));

            TableColumn<FamilyRecord, String> nameCol = new TableColumn<FamilyRecord, String>("名称");
            nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
            nameCol.setPrefWidth(140);
            nameCol.setCellFactory(col -> new FamilyTextCell(false, false));
            TableColumn<FamilyRecord, String> englishCol = new TableColumn<FamilyRecord, String>("英文");
            englishCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEnglishName()));
            englishCol.setPrefWidth(180);
            englishCol.setCellFactory(col -> new FamilyTextCell(true, false));
            TableColumn<FamilyRecord, String> descCol = new TableColumn<FamilyRecord, String>("描述");
            descCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescription()));
            descCol.setPrefWidth(260);
            descCol.setCellFactory(col -> new FamilyTextCell(false, true));

            TableColumn<FamilyRecord, FamilyRecord> actionCol = new TableColumn<FamilyRecord, FamilyRecord>("操作");
            actionCol.setPrefWidth(200);
            actionCol.setSortable(false);
            actionCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<FamilyRecord>(c.getValue()));
            actionCol.setCellFactory(col -> new TableCell<FamilyRecord, FamilyRecord>() {
                private final Button pageKindBtn = AppIcons.iconButton(
                        AppIcons.Kind.PAGE_TYPES, "页面类型", true);
                private final Button outputDefBtn = AppIcons.iconButton(
                        AppIcons.Kind.OUTPUT, "输出定义", true);
                private final Button templateBtn = AppIcons.iconButton(
                        AppIcons.Kind.TEMPLATE, "输出模板", true);
                private final Button exportBtn = AppIcons.iconButton(
                        AppIcons.Kind.EXPORT, "导出模板包", true);
                private final Button deleteBtn = AppIcons.iconButton(
                        AppIcons.Kind.DELETE, "删除", false);

                {
                    pageKindBtn.setOnAction(e -> {
                        FamilyRecord family = getItem();
                        if (family != null) {
                            window.showPageKindManage(family);
                        }
                    });
                    outputDefBtn.setOnAction(e -> {
                        FamilyRecord family = getItem();
                        if (family != null) {
                            window.showFamilyOutput(family);
                        }
                    });
                    templateBtn.setOnAction(e -> {
                        FamilyRecord family = getItem();
                        if (family != null) {
                            chooseExcelTemplate(family);
                        }
                    });
                    exportBtn.setOnAction(e -> {
                        FamilyRecord family = getItem();
                        if (family != null) {
                            exportFamilyPack(family);
                        }
                    });
                    deleteBtn.setOnAction(e -> {
                        FamilyRecord family = getItem();
                        if (family != null) {
                            confirmDeleteFamily(family);
                        }
                    });
                }

                @Override
                protected void updateItem(FamilyRecord item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                        return;
                    }
                    String tip = item.hasExcelTemplate()
                            ? "输出模板：\n" + item.getExcelTemplatePath()
                            : "输出模板（未配置）";
                    templateBtn.setTooltip(AppIcons.styledTooltip(tip));
                    outputDefBtn.setTooltip(AppIcons.styledTooltip("输出定义（JSON，文档结构）"));
                    exportBtn.setTooltip(AppIcons.styledTooltip("导出模板包（页面类型、坐标、输出定义、Excel）"));
                    HBox box = new HBox(6, pageKindBtn, outputDefBtn, templateBtn, exportBtn, deleteBtn);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                }
            });

            table.setItems(items);
            table.getColumns().add(nameCol);
            table.getColumns().add(englishCol);
            table.getColumns().add(descCol);
            table.getColumns().add(actionCol);
            table.setPlaceholder(new Label("暂无文件类型"));
            table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
            table.getStyleClass().add("settings-table");
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

            Button addFamily = AppIcons.iconButton(AppIcons.Kind.ADD, "新增文件类型", true);
            addFamily.setOnAction(e -> showAddForm());
            Button importPack = AppIcons.iconButton(AppIcons.Kind.IMPORT, "导入模板包", true);
            importPack.setOnAction(e -> importFamilyPack());

            Label title = new Label("文件类型列表");
            title.getStyleClass().add("settings-section-title");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox toolbar = new HBox(10, title, spacer, importPack, addFamily);
            toolbar.setAlignment(Pos.CENTER_LEFT);

            listPanel.getChildren().addAll(toolbar, table);
            VBox.setVgrow(table, Priority.ALWAYS);
            table.setMaxHeight(Double.MAX_VALUE);
        }

        void showList() {
            reload();
            root.setCenter(listPanel);
        }

        void showAddForm() {
            addFormPane.clear();
            root.setCenter(addFormPane.getRoot());
        }

        private void saveNewFamily() {
            FamilyRecord record = addFormPane.readRecord();
            if (record == null) {
                showInfo(getWindow(), "请填写代码、名称和英文");
                return;
            }
            try {
                runtime.getDatabase().addDocumentFamily(
                        record.getCode(),
                        record.getName(),
                        record.getEnglishName(),
                        record.getDescription());
                runtime.reloadCatalog();
                notifySaved();
                showList();
            } catch (SQLException ex) {
                showError(getWindow(), "保存文件类型失败", ex);
            }
        }

        private void confirmDeleteFamily(FamilyRecord family) {
            try {
                if (runtime.listFamilies().size() <= 1) {
                    showInfo(getWindow(), "至少保留一个文件类型");
                    return;
                }
            } catch (SQLException ex) {
                showError(getWindow(), "加载文件类型失败", ex);
                return;
            }
            if (!SettingsDialogs.confirm(getWindow(), "删除文件类型",
                    "确定删除「" + family.getName() + "」及其全部页面类型？")) {
                return;
            }
            try {
                boolean wasCurrent = runtime.getFamilyCode().equals(family.getCode());
                runtime.getDatabase().deleteDocumentFamily(family.getCode());
                List<FamilyRecord> remaining = runtime.listFamilies();
                if (wasCurrent && !remaining.isEmpty()) {
                    runtime.selectFamily(remaining.get(0).getCode());
                } else {
                    runtime.reloadCatalog();
                }
                reload();
                notifySaved();
            } catch (SQLException ex) {
                showError(getWindow(), "删除文件类型失败", ex);
            }
        }

        BorderPane getRoot() {
            return root;
        }

        void reload() {
            try {
                items.setAll(runtime.listFamilies());
                if (!items.isEmpty() && table.getSelectionModel().getSelectedItem() == null) {
                    for (FamilyRecord family : items) {
                        if (runtime.getFamilyCode().equals(family.getCode())) {
                            table.getSelectionModel().select(family);
                            return;
                        }
                    }
                    table.getSelectionModel().select(0);
                }
            } catch (SQLException ex) {
                showError(getWindow(), "加载文件类型失败", ex);
            }
        }

        private void updateFamilyMeta(FamilyRecord family, String name, String englishName, String description) {
            try {
                runtime.getDatabase().updateDocumentFamily(
                        family.getCode(),
                        name != null ? name : family.getName(),
                        englishName != null ? englishName : family.getEnglishName(),
                        description != null ? description : family.getDescription());
                runtime.reloadCatalog();
                reload();
                notifySaved();
            } catch (SQLException ex) {
                showError(getWindow(), "更新文件类型失败", ex);
            }
        }

        /** 一种文件类型绑定一份 Excel 输出模板。 */
        private void chooseExcelTemplate(FamilyRecord family) {
            if (family.hasExcelTemplate()) {
                SettingsDialogs.TemplateAction action = SettingsDialogs.chooseExcelTemplateAction(
                        getWindow(), family.getName(), family.getExcelTemplatePath());
                if (action == SettingsDialogs.TemplateAction.CANCEL) {
                    return;
                }
                if (action == SettingsDialogs.TemplateAction.CLEAR) {
                    saveExcelTemplate(family, "");
                    return;
                }
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择「" + family.getName() + "」输出模板");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Excel 模板 (*.xlsx)", "*.xlsx"));
            if (family.hasExcelTemplate()) {
                Path current = Path.of(family.getExcelTemplatePath());
                if (Files.isRegularFile(current) && current.getParent() != null) {
                    chooser.setInitialDirectory(current.getParent().toFile());
                    chooser.setInitialFileName(current.getFileName().toString());
                }
            }
            File picked = chooser.showOpenDialog(getWindow());
            if (picked == null) {
                return;
            }
            saveExcelTemplate(family, picked.getAbsolutePath());
        }

        private void saveExcelTemplate(FamilyRecord family, String path) {
            try {
                runtime.getDatabase().updateDocumentFamilyExcelTemplate(family.getCode(), path);
                runtime.reloadCatalog();
                reload();
                notifySaved();
            } catch (SQLException ex) {
                showError(getWindow(), "保存输出模板失败", ex);
            }
        }

        private void exportFamilyPack(FamilyRecord family) {
            if (!window.getLicenseService().allows(LicenseFeatures.TEMPLATE_PACK)) {
                showInfo(getWindow(), window.getLicenseService().denyMessage(LicenseFeatures.TEMPLATE_PACK));
                return;
            }
            if (!family.hasExcelTemplate()
                    || !Files.isRegularFile(Path.of(family.getExcelTemplatePath()))) {
                if (!SettingsDialogs.confirm(getWindow(), "导出模板包",
                        "当前未绑定可用的 Excel 模板，包内将不含 Excel。"
                                + "\n对方写出 Excel 前需自行配置。是否继续导出？")) {
                    return;
                }
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("导出「" + family.getName() + "」模板包");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(
                            "WeaveLay 模板包 (*" + FamilyTemplatePackService.EXTENSION + ")",
                            "*" + FamilyTemplatePackService.EXTENSION));
            String safeName = family.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            chooser.setInitialFileName(safeName + "-" + family.getCode()
                    + FamilyTemplatePackService.EXTENSION);
            File dest = chooser.showSaveDialog(getWindow());
            if (dest == null) {
                return;
            }
            Path destPath = dest.toPath();
            if (!destPath.getFileName().toString().endsWith(FamilyTemplatePackService.EXTENSION)) {
                destPath = destPath.resolveSibling(
                        destPath.getFileName().toString() + FamilyTemplatePackService.EXTENSION);
            }
            try {
                FamilyTemplatePackService pack = new FamilyTemplatePackService(runtime.getDatabase());
                boolean hasExcel = pack.exportFamily(family.getCode(), destPath);
                showInfo(getWindow(), "已导出模板包"
                        + (hasExcel ? "（含 Excel）" : "（无 Excel）")
                        + "：\n" + destPath);
            } catch (FamilyTemplatePackException | SQLException ex) {
                showError(getWindow(), "导出模板包失败", ex);
            }
        }

        private void importFamilyPack() {
            if (!window.getLicenseService().allows(LicenseFeatures.TEMPLATE_PACK)) {
                showInfo(getWindow(), window.getLicenseService().denyMessage(LicenseFeatures.TEMPLATE_PACK));
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("导入模板包");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter(
                            "WeaveLay 模板包 (*" + FamilyTemplatePackService.EXTENSION + ")",
                            "*" + FamilyTemplatePackService.EXTENSION),
                    new FileChooser.ExtensionFilter("Zip (*.zip)", "*.zip"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            File picked = chooser.showOpenDialog(getWindow());
            if (picked == null) {
                return;
            }
            Path packPath = picked.toPath();
            FamilyTemplatePackService pack = new FamilyTemplatePackService(runtime.getDatabase());
            try {
                FamilyTemplatePackService.PackManifest manifest = pack.peekManifest(packPath);
                boolean overwrite = false;
                if (runtime.getDatabase().familyExists(manifest.familyCode)) {
                    if (!SettingsDialogs.confirm(getWindow(), "覆盖文件类型",
                            "本地已有文件类型「"
                                    + (manifest.name == null || manifest.name.isBlank()
                                    ? manifest.familyCode
                                    : manifest.name)
                                    + "」（" + manifest.familyCode + "）。"
                                    + "\n覆盖将替换其页面类型、坐标、输出定义与 Excel 绑定。是否继续？")) {
                        return;
                    }
                    overwrite = true;
                }
                FamilyTemplatePackResult result = pack.importPack(packPath, overwrite);
                runtime.reloadCatalog();
                reload();
                notifySaved();
                showInfo(getWindow(), "导入成功：\n" + result.summary());
            } catch (FamilyTemplatePackException ex) {
                if (ex.getReason() == FamilyTemplatePackException.Reason.FAMILY_EXISTS) {
                    showInfo(getWindow(), ex.getMessage());
                } else {
                    showError(getWindow(), "导入模板包失败", ex);
                }
            } catch (SQLException ex) {
                showError(getWindow(), "导入模板包失败", ex);
            }
        }

        /** 可双击编辑的 TableCell, 用于 Family 名称/英文/描述. */
        private final class FamilyTextCell extends TableCell<FamilyRecord, String> {

            private final TextField field = new TextField();
            private final boolean englishColumn;
            private final boolean descriptionColumn;
            private boolean editing;

            FamilyTextCell(boolean englishColumn, boolean descriptionColumn) {
                this.englishColumn = englishColumn;
                this.descriptionColumn = descriptionColumn;
                field.getStyleClass().add("settings-table-field");
                field.setOnAction(e -> commitAndStopEditing());
                field.setOnKeyPressed(e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        cancelEditing();
                    }
                });
                field.focusedProperty().addListener((obs, wasFocused, focused) -> {
                    if (!focused && editing) {
                        commitAndStopEditing();
                    }
                });
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !isEmpty() && !isEditing()) {
                        startEditing();
                    }
                });
            }

            @Override
            public void startEdit() { startEditing(); }
            @Override
            public void cancelEdit() { cancelEditing(); }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    editing = false;
                    setGraphic(null);
                    setText(null);
                    getStyleClass().remove("settings-table-placeholder-cell");
                    return;
                }
                if (editing) {
                    if (!field.isFocused()) {
                        field.setText(item == null ? "" : item);
                    }
                    setGraphic(field);
                    setText(null);
                    return;
                }
                setGraphic(null);
                getStyleClass().remove("settings-table-placeholder-cell");
                if (descriptionColumn && (item == null || item.trim().isEmpty())) {
                    setText("双击编辑");
                    getStyleClass().add("settings-table-placeholder-cell");
                } else {
                    setText(item == null ? "" : item);
                }
            }

            private void startEditing() {
                if (isEmpty()) return;
                editing = true;
                String value = getItem();
                field.setText(value == null ? "" : value);
                setText(null);
                setGraphic(field);
                field.requestFocus();
                field.selectAll();
            }

            private void commitAndStopEditing() {
                if (!editing) return;
                commitIfChanged();
                editing = false;
                // 刷新显示 (updateItem 会被 TableView 重新调用)
                setGraphic(null);
                String item = getItem();
                getStyleClass().remove("settings-table-placeholder-cell");
                if (descriptionColumn && (item == null || item.trim().isEmpty())) {
                    setText("双击编辑");
                    getStyleClass().add("settings-table-placeholder-cell");
                } else {
                    setText(item == null ? "" : item);
                }
            }

            private void cancelEditing() {
                editing = false;
                String item = getItem();
                setGraphic(null);
                getStyleClass().remove("settings-table-placeholder-cell");
                if (descriptionColumn && (item == null || item.trim().isEmpty())) {
                    setText("双击编辑");
                    getStyleClass().add("settings-table-placeholder-cell");
                } else {
                    setText(item == null ? "" : item);
                }
            }

            private void commitIfChanged() {
                FamilyRecord family = getTableRow() == null ? null : getTableRow().getItem();
                if (family == null) return;
                String text = field.getText().trim();
                if (englishColumn) {
                    if (text.equals(family.getEnglishName())) return;
                    updateFamilyMeta(family, null, text, null);
                } else if (descriptionColumn) {
                    if (text.equals(family.getDescription())) return;
                    updateFamilyMeta(family, null, null, text);
                } else {
                    if (text.equals(family.getName())) return;
                    updateFamilyMeta(family, text, null, null);
                }
            }
        }
    }

    /**
     * 页面类型列表；输出定义单独一页 (同文件类型→页面类型切换方式).
     */
    private final class PageKindManagePane {

        private final SettingsWindow window;
        private final BorderPane root = new BorderPane();
        private final VBox listPanel = new VBox(8);
        private final VBox outputPanel = new VBox(8);
        private final Label familyTitle = new Label();
        private final Label outputKindTitle = new Label();
        private final TableView<PageKindRecord> table = new TableView<PageKindRecord>();
        private final ObservableList<PageKindRecord> items = FXCollections.observableArrayList();
        private final TextArea outputArea = new TextArea();

        private FamilyRecord family;
        private boolean suppressDetailSave;
        private String loadedOutputKindCode = "";

        private PageKindManagePane(SettingsWindow window) {
            this.window = window;
            root.setPadding(new Insets(12));
            buildListPanel();
            buildOutputPanel();
            showListPanel();
        }

        private void buildListPanel() {
            table.getStyleClass().add("settings-table");
            familyTitle.getStyleClass().add("settings-section-title");

            TableColumn<PageKindRecord, Void> seqCol = new TableColumn<PageKindRecord, Void>("序号");
            seqCol.setPrefWidth(52);
            seqCol.setSortable(false);
            seqCol.setCellFactory(col -> new TableCell<PageKindRecord, Void>() {
                @Override
                protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                        setText(null);
                    } else {
                        setText(String.valueOf(getIndex() + 1));
                    }
                }
            });

            TableColumn<PageKindRecord, String> nameCol = new TableColumn<PageKindRecord, String>("名称");
            nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDisplayName()));
            nameCol.setPrefWidth(140);
            nameCol.setSortable(false);
            nameCol.setCellFactory(col -> new PageKindTextCell(false));

            TableColumn<PageKindRecord, String> descCol = new TableColumn<PageKindRecord, String>("描述");
            descCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDescription()));
            descCol.setPrefWidth(220);
            descCol.setSortable(false);
            descCol.setCellFactory(col -> new PageKindTextCell(true));

            TableColumn<PageKindRecord, PageKindRecord> actionCol =
                    new TableColumn<PageKindRecord, PageKindRecord>("操作");
            actionCol.setPrefWidth(108);
            actionCol.setSortable(false);
            actionCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<PageKindRecord>(c.getValue()));
            actionCol.setCellFactory(col -> new TableCell<PageKindRecord, PageKindRecord>() {
                private final Button upBtn = AppIcons.iconButton(AppIcons.Kind.MOVE_UP, "上移", false);
                private final Button downBtn = AppIcons.iconButton(AppIcons.Kind.MOVE_DOWN, "下移", false);
                private final Button outputBtn = AppIcons.iconButton(AppIcons.Kind.OUTPUT, "输出定义", false);
                private PageKindRecord bound;

                {
                    upBtn.setOnAction(e -> {
                        e.consume();
                        PageKindRecord kind = resolveBoundKind();
                        if (kind != null) {
                            movePageKind(kind, -1);
                        }
                    });
                    downBtn.setOnAction(e -> {
                        e.consume();
                        PageKindRecord kind = resolveBoundKind();
                        if (kind != null) {
                            movePageKind(kind, 1);
                        }
                    });
                    outputBtn.setOnAction(e -> {
                        e.consume();
                        PageKindRecord kind = resolveBoundKind();
                        if (kind != null) {
                            showOutputPanel(kind);
                        }
                    });
                }

                private PageKindRecord resolveBoundKind() {
                    if (bound != null) {
                        return bound;
                    }
                    if (getItem() != null) {
                        return getItem();
                    }
                    return getTableRow() == null ? null : getTableRow().getItem();
                }

                @Override
                protected void updateItem(PageKindRecord item, boolean empty) {
                    super.updateItem(item, empty);
                    bound = empty ? null : item;
                    if (empty || item == null) {
                        setGraphic(null);
                        return;
                    }
                    int index = getIndex();
                    upBtn.setDisable(index <= 0);
                    downBtn.setDisable(index < 0 || index >= items.size() - 1);
                    HBox box = new HBox(4, upBtn, downBtn, outputBtn);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                }
            });

            table.setItems(items);
            // 禁止表头排序：本列表顺序由上移/下移维护
            table.setSortPolicy(tv -> false);
            table.getColumns().add(seqCol);
            table.getColumns().add(nameCol);
            table.getColumns().add(descCol);
            table.getColumns().add(actionCol);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.setPlaceholder(new Label("暂无页面类型"));

            Button back = AppIcons.iconButton(AppIcons.Kind.BACK, "返回文件类型", true);
            back.setOnAction(e -> window.showFamilyManage());
            Button addKind = AppIcons.iconButton(AppIcons.Kind.ADD, "新增页面类型", true);
            addKind.setOnAction(e -> promptAddPageKind());

            HBox top = new HBox(10, back, familyTitle);
            top.setAlignment(Pos.CENTER_LEFT);

            Label listTitle = new Label("页面类型列表");
            listTitle.getStyleClass().add("settings-section-title");
            Region listSpacer = new Region();
            HBox.setHgrow(listSpacer, Priority.ALWAYS);
            HBox listToolbar = new HBox(10, listTitle, listSpacer, addKind);
            listToolbar.setAlignment(Pos.CENTER_LEFT);

            listPanel.setPadding(new Insets(0));
            VBox.setVgrow(table, Priority.ALWAYS);
            listPanel.getChildren().addAll(top, listToolbar, table);
            VBox.setVgrow(table, Priority.ALWAYS);
        }

        private void buildOutputPanel() {
            outputKindTitle.getStyleClass().add("settings-section-title");

            Label outputHint = new Label("JSON格式输出定义；支持普通字段和表格；失焦自动保存");
            outputHint.getStyleClass().add("settings-hint");
            outputHint.setWrapText(true);

            Label formatHint = new Label("示例见输入框");
            formatHint.getStyleClass().add("settings-output-example");

            outputArea.getStyleClass().add("settings-form-textarea");
            outputArea.setPromptText("[\n  {\"name\":\"产品代号\",\"type\":\"text\"},\n  {\"name\":\"工序表\",\"type\":\"table\",\"columns\":[\"工序号\",\"工序名称\"]}\n]");
            outputArea.setWrapText(false);
            outputArea.focusedProperty().addListener((obs, wasFocused, focused) -> {
                if (!focused) {
                    saveOutputDefinition();
                }
            });

            Button back = AppIcons.iconButton(AppIcons.Kind.BACK, "返回页面类型列表", true);
            back.setOnAction(e -> {
                saveOutputDefinition();
                showListPanel();
            });

            HBox top = new HBox(10, back, outputKindTitle);
            top.setAlignment(Pos.CENTER_LEFT);

            VBox editorCard = new VBox(10, outputHint, formatHint, outputArea);
            editorCard.getStyleClass().add("settings-output-card");
            VBox.setVgrow(outputArea, Priority.ALWAYS);

            outputPanel.getChildren().addAll(top, editorCard);
            VBox.setVgrow(editorCard, Priority.ALWAYS);
        }

        private void showListPanel() {
            loadedOutputKindCode = "";
            root.setCenter(listPanel);
        }

        private void showOutputPanel(PageKindRecord kind) {
            suppressDetailSave = true;
            try {
                loadedOutputKindCode = kind.getCode();
                outputKindTitle.setText("输出定义 · " + kind.getDisplayName());
                outputArea.setText(SlotDefinitionFormat.format(
                        runtime.getDatabase().listSlots(kind.getCode())));
                root.setCenter(outputPanel);
                outputArea.requestFocus();
            } catch (SQLException ex) {
                showError(getWindow(), "加载输出定义失败", ex);
            } finally {
                suppressDetailSave = false;
            }
        }

        BorderPane getRoot() {
            return root;
        }

        void load(FamilyRecord family) {
            this.family = family;
            familyTitle.setText("文件类型: " + family.getName() + " / " + family.getEnglishName());
            showListPanel();
            reloadKinds();
        }

        private void reloadKinds() {
            try {
                items.setAll(runtime.getDatabase().listPageKinds(family.getCode()));
                if (!items.isEmpty()) {
                    table.getSelectionModel().select(0);
                }
            } catch (SQLException ex) {
                showError(getWindow(), "加载页面类型失败", ex);
            }
        }

        private void saveOutputDefinition() {
            if (suppressDetailSave || loadedOutputKindCode.isEmpty()) {
                return;
            }
            try {
                List<SlotDefinition> parsed = SlotDefinitionFormat.parse(outputArea.getText());
                // 保留已有坐标：用 label 匹配，坐标不丢
                List<SlotDefinition> existing = runtime.getDatabase().loadSlots(loadedOutputKindCode);
                for (int i = 0; i < parsed.size(); i++) {
                    SlotDefinition p = parsed.get(i);
                    for (SlotDefinition e : existing) {
                        if (p.getSlotLabel().equals(e.getSlotLabel()) && e.hasRegion()) {
                            parsed.set(i, new SlotDefinition(p.getSlotCode(), p.getSlotLabel(), i,
                                    e.getRegionX(), e.getRegionY(), e.getRegionW(), e.getRegionH(),
                                    p.getFieldType(), p.getFieldMeta()));
                            break;
                        }
                    }
                }
                runtime.getDatabase().replaceSlotDefinitions(loadedOutputKindCode, parsed);
                runtime.reloadCatalog();
                notifySaved();
            } catch (SQLException ex) {
                showError(getWindow(), "保存输出定义失败", ex);
            }
        }

        private void savePageKindMeta(PageKindRecord kind, String displayName, String description) {
            try {
                runtime.getDatabase().updatePageKindMeta(kind.getCode(), displayName, description);
                runtime.reloadCatalog();
                int index = items.indexOf(kind);
                PageKindRecord updated = new PageKindRecord(
                        kind.getCode(),
                        displayName,
                        description,
                        kind.getFamilyCode(),
                        kind.getClassifyPriority());
                if (index >= 0) {
                    items.set(index, updated);
                    table.getSelectionModel().select(updated);
                }
                notifySaved();
            } catch (SQLException ex) {
                showError(getWindow(), "保存页面类型失败", ex);
            }
        }

        private final class PageKindTextCell extends TableCell<PageKindRecord, String> {

            private final TextField field = new TextField();
            private final boolean descriptionColumn;
            private boolean editing;

            private PageKindTextCell(boolean descriptionColumn) {
                this.descriptionColumn = descriptionColumn;
                field.getStyleClass().add("settings-table-field");
                field.setOnAction(e -> commitAndStopEditing());
                field.setOnKeyPressed(e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        cancelEditing();
                    }
                });
                field.focusedProperty().addListener((obs, wasFocused, focused) -> {
                    if (!focused && editing) {
                        commitAndStopEditing();
                    }
                });
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !isEmpty() && !isEditing()) {
                        startEditing();
                    }
                });
            }

            @Override
            public void startEdit() {
                startEditing();
            }

            @Override
            public void cancelEdit() {
                cancelEditing();
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    editing = false;
                    setGraphic(null);
                    setText(null);
                    getStyleClass().remove("settings-table-placeholder-cell");
                    return;
                }
                if (editing) {
                    if (!field.isFocused()) {
                        field.setText(item == null ? "" : item);
                    }
                    setGraphic(field);
                    setText(null);
                    return;
                }
                showDisplayText(item);
            }

            private void showDisplayText(String item) {
                setGraphic(null);
                getStyleClass().remove("settings-table-placeholder-cell");
                if (descriptionColumn && (item == null || item.trim().isEmpty())) {
                    setText("双击编辑");
                    getStyleClass().add("settings-table-placeholder-cell");
                } else {
                    setText(item == null ? "" : item);
                }
            }

            private void startEditing() {
                if (isEmpty()) {
                    return;
                }
                editing = true;
                String value = getItem();
                field.setText(value == null ? "" : value);
                setText(null);
                setGraphic(field);
                field.requestFocus();
                field.selectAll();
            }

            private void commitAndStopEditing() {
                if (!editing) {
                    return;
                }
                commitIfChanged();
                editing = false;
                showDisplayText(getItem());
            }

            private void cancelEditing() {
                editing = false;
                showDisplayText(getItem());
            }

            private void commitIfChanged() {
                PageKindRecord kind = getTableRow() == null ? null : getTableRow().getItem();
                if (kind == null) {
                    return;
                }
                String text = field.getText().trim();
                if (descriptionColumn) {
                    if (text.equals(kind.getDescription())) {
                        return;
                    }
                    savePageKindMeta(kind, kind.getDisplayName(), text);
                } else {
                    if (text.equals(kind.getDisplayName())) {
                        return;
                    }
                    savePageKindMeta(kind, text, kind.getDescription());
                }
            }
        }

        private void movePageKind(PageKindRecord kind, int delta) {
            if (family == null || kind == null) {
                return;
            }
            int idx = -1;
            for (int i = 0; i < items.size(); i++) {
                if (kind.getCode().equals(items.get(i).getCode())) {
                    idx = i;
                    break;
                }
            }
            int other = idx + delta;
            if (idx < 0 || other < 0 || other >= items.size()) {
                return;
            }
            String code = kind.getCode();
            String neighborCode = items.get(other).getCode();
            try {
                runtime.getDatabase().swapPageKindPriorities(code, neighborCode);
                runtime.reloadCatalog();
                reloadKindsKeepingSelection(code);
                notifySaved();
            } catch (SQLException ex) {
                showError(getWindow(), "调整页面类型顺序失败", ex);
            }
        }

        private void reloadKindsKeepingSelection(String selectCode) {
            try {
                items.setAll(runtime.getDatabase().listPageKinds(family.getCode()));
                if (items.isEmpty()) {
                    return;
                }
                int selectIdx = 0;
                if (selectCode != null) {
                    for (int i = 0; i < items.size(); i++) {
                        if (selectCode.equals(items.get(i).getCode())) {
                            selectIdx = i;
                            break;
                        }
                    }
                }
                table.getSelectionModel().select(selectIdx);
                table.scrollTo(selectIdx);
            } catch (SQLException ex) {
                showError(getWindow(), "加载页面类型失败", ex);
            }
        }

        private void promptAddPageKind() {
            if (family == null) {
                return;
            }
            javafx.scene.control.Dialog<PageKindRecord> dialog = new javafx.scene.control.Dialog<PageKindRecord>();
            dialog.setTitle("新增页面类型");
            dialog.initOwner(getWindow());
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            TextField codeField = new TextField();
            codeField.setPromptText("code");
            codeField.getStyleClass().add("settings-form-field");
            TextField nameField = new TextField();
            nameField.setPromptText("显示名");
            nameField.getStyleClass().add("settings-form-field");
            TextField descField = new TextField();
            descField.setPromptText("描述（可选）");
            descField.getStyleClass().add("settings-form-field");
            ComboBox<String> pairMode = new ComboBox<String>();
            pairMode.getItems().addAll("NONE", "HORIZONTAL", "VERTICAL");
            pairMode.setValue("NONE");
            pairMode.getStyleClass().add("settings-form-field");

            Label heading = new Label("填写页面类型基本信息");
            heading.getStyleClass().add("settings-dialog-heading");

            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(12);
            grid.setVgap(10);
            grid.addRow(0, formLabel("代码"), codeField);
            grid.addRow(1, formLabel("名称"), nameField);
            grid.addRow(2, formLabel("描述"), descField);
            grid.addRow(3, formLabel("配对"), pairMode);
            grid.setPadding(new Insets(4, 0, 0, 0));

            VBox body = new VBox(14, heading, grid);
            body.setPadding(new Insets(4, 4, 4, 4));
            dialog.getDialogPane().setContent(body);
            dialog.getDialogPane().setPrefWidth(440);
            SettingsDialogs.styleDialog(dialog);

            dialog.setResultConverter(btn -> {
                if (btn != ButtonType.OK) {
                    return null;
                }
                String code = codeField.getText().trim();
                String name = nameField.getText().trim();
                if (code.isEmpty() || name.isEmpty()) {
                    return null;
                }
                return new PageKindRecord(
                        code,
                        name,
                        descField.getText().trim(),
                        family.getCode(),
                        0);
            });

            dialog.showAndWait().ifPresent(record -> {
                try {
                    runtime.getDatabase().addPageKind(
                            family.getCode(),
                            record.getCode(),
                            record.getDisplayName(),
                            record.getDescription(),
                            pairMode.getValue());
                    runtime.reloadCatalog();
                    reloadKinds();
                    notifySaved();
                } catch (SQLException ex) {
                    showError(getWindow(), "保存页面类型失败", ex);
                }
            });
        }

        private static Label formLabel(String text) {
            Label l = new Label(text);
            l.getStyleClass().add("settings-form-label");
            l.setMinWidth(96);
            return l;
        }
    }
}
