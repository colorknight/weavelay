package com.weavelay.app.ui;

import com.weavelay.app.AppPreferences;
import com.weavelay.ocr.OcrParamSettings;
import com.weavelay.ocr.OcrRuntimeConfig;
import com.weavelay.ocr.RapidOcrService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * 设置窗口: RapidOCR 全局参数.
 */
final class OcrSettingsPane {

    private static final double FIELD_WIDTH = 200;

    private final VBox root = new VBox(12);
    private final AppPreferences preferences;
    private final RapidOcrService ocrService;
    private final Window ownerWindow;
    private final Runnable onSaved;

    private final TextField paddingField = new TextField();
    private final TextField maxSideLenField = new TextField();
    private final TextField boxScoreThreshField = new TextField();
    private final TextField boxThreshField = new TextField();
    private final TextField unClipRatioField = new TextField();
    private final TextField minBoxScoreField = new TextField();
    private final TextField exportParallelismField = new TextField();
    private final Label saveStatusLabel = new Label("");

    OcrSettingsPane(
            AppPreferences preferences,
            RapidOcrService ocrService,
            Window ownerWindow,
            Runnable onSaved) {
        this.preferences = preferences;
        this.ocrService = ocrService;
        this.ownerWindow = ownerWindow;
        this.onSaved = onSaved;
        build();
        loadFromPreferences();
    }

    VBox getRoot() {
        return root;
    }

    private void build() {
        root.getStyleClass().add("settings-form-pane");
        root.setPadding(new Insets(12, 16, 16, 16));

        Label title = new Label("OCR 参数");
        title.getStyleClass().add("settings-section-title");

        Label intro = new Label(
                "PP-OCRv6 模型，改参数后需重新运行 PDF。"
                        + "首次运行会自动下载 OCR 引擎 (约 7MB), 需联网。"
                        + "公式识别仅用本地 PP-FormulaNet ONNX，无 HTTP。");
        intro.getStyleClass().add("settings-hint");
        intro.setWrapText(true);
        intro.setMaxWidth(Double.MAX_VALUE);

        VBox form = new VBox(16);
        form.getStyleClass().add("settings-ocr-form");
        form.setPadding(new Insets(8, 0, 0, 0));
        form.getChildren().addAll(
                paramBlock("maxSideLen", maxSideLenField,
                        "图像长边上限 (像素)。超过则等比缩小再检测。"
                                + "0 = 自动：小字段不缩；大表裁剪内部用 2000+Det1200（更快且保细数字）。"
                                + "显式设 1440/2000 则两档都用该上限。"),
                paramBlock("boxScoreThresh", boxScoreThreshField,
                        "检测框保留门槛 (0~1)。越小召回越高、杂框越多。"
                                + "漏检多时可试 0.35~0.45，默认 0.5。"),
                paramBlock("boxThresh", boxThreshField,
                        "检测二值化阈值 (0~1)。越大检测区域越收缩，一般保持 0.3，微调即可。"),
                paramBlock("unClipRatio", unClipRatioField,
                        "检测框扩张倍率。框太紧裁字时可加大，可试 1.8~2.0，默认 1.6。"),
                paramBlock("padding", paddingField,
                        "图像四周白边 (像素)。略增有助于贴边文字，默认 50。"),
                paramBlock("minBoxScore", minBoxScoreField,
                        "Java 后处理: 低于此检测分的框丢弃 (0~1)。"
                                + "0 = 不过滤; 仅影响已检测出的框。"),
                paramBlock("exportParallelism", exportParallelismField,
                        "导出并行 Worker 数。每个 Worker 是独立 OCR 进程，"
                                + "占用约 300MB 内存。建议 2~4，默认 2。"));

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().add("settings-toolbar-btn");
        saveBtn.setOnAction(e -> save());

        saveStatusLabel.getStyleClass().add("settings-save-status");
        saveStatusLabel.setAlignment(Pos.CENTER_LEFT);
        saveStatusLabel.setMinWidth(48);

        Button resetBtn = new Button("恢复默认");
        resetBtn.getStyleClass().add("settings-toolbar-btn");
        resetBtn.setOnAction(e -> {
            saveStatusLabel.setText("");
            loadDefaults();
        });

        HBox buttons = new HBox(10, saveBtn, resetBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        saveStatusLabel.setWrapText(true);
        saveStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(saveStatusLabel, Priority.ALWAYS);

        HBox actions = new HBox(16, buttons, saveStatusLabel);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setMaxWidth(Double.MAX_VALUE);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(title, intro, scroll, actions);
    }

    private static VBox paramBlock(String name, TextField field, String help) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("settings-field-label");
        nameLabel.setMinWidth(130);

        styleField(field, name);

        HBox inputRow = new HBox(12, nameLabel, field);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        Label helpLabel = new Label(help);
        helpLabel.getStyleClass().add("settings-field-help");
        helpLabel.setWrapText(true);
        helpLabel.setMaxWidth(Double.MAX_VALUE);

        VBox block = new VBox(6, inputRow, helpLabel);
        block.getStyleClass().add("settings-ocr-param");
        return block;
    }

    private static void styleField(TextField field, String name) {
        field.setPromptText(name);
        field.setPrefWidth(FIELD_WIDTH);
        field.setMinWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
    }

    private void loadFromPreferences() {
        fillFields(preferences.getOcrParams());
        exportParallelismField.setText(String.valueOf(preferences.getExportParallelism()));
    }

    private void loadDefaults() {
        fillFields(OcrParamSettings.defaults());
        exportParallelismField.setText("2");
    }

    private void fillFields(OcrParamSettings params) {
        paddingField.setText(String.valueOf(params.getPadding()));
        maxSideLenField.setText(String.valueOf(params.getMaxSideLen()));
        boxScoreThreshField.setText(String.valueOf(params.getBoxScoreThresh()));
        boxThreshField.setText(String.valueOf(params.getBoxThresh()));
        unClipRatioField.setText(String.valueOf(params.getUnClipRatio()));
        minBoxScoreField.setText(String.valueOf(params.getMinBoxScore()));
    }

    private void save() {
        try {
            OcrParamSettings params = new OcrParamSettings(
                    parseInt(paddingField, OcrParamSettings.DEFAULT_PADDING),
                    parseInt(maxSideLenField, OcrParamSettings.DEFAULT_MAX_SIDE_LEN),
                    parseFloat(boxScoreThreshField, OcrParamSettings.DEFAULT_BOX_SCORE_THRESH),
                    parseFloat(boxThreshField, OcrParamSettings.DEFAULT_BOX_THRESH),
                    parseFloat(unClipRatioField, OcrParamSettings.DEFAULT_UNCLIP_RATIO),
                    parseFloat(minBoxScoreField, OcrParamSettings.DEFAULT_MIN_BOX_SCORE));
            OcrRuntimeConfig runtime = new OcrRuntimeConfig();
            ocrService.validateRuntime(runtime);
            preferences.saveOcrSettings(params, runtime);
            preferences.saveExportParallelism(
                    parseInt(exportParallelismField, 2));
            ocrService.applyParams(params);
            ocrService.applyRuntime(runtime);
            if (onSaved != null) {
                onSaved.run();
            }
            saveStatusLabel.setText("已保存");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            saveStatusLabel.setText("");
            SettingsWindow.showError(ownerWindow, "参数无效", ex);
        } catch (Exception ex) {
            saveStatusLabel.setText("");
            SettingsWindow.showError(ownerWindow, "OCR 参数保存失败", ex);
        }
    }

    private static int parseInt(TextField field, int fallback) {
        String text = field.getText();
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field.getPromptText() + " 须为整数");
        }
    }

    private static float parseFloat(TextField field, float fallback) {
        String text = field.getText();
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("须为数字");
        }
    }
}
