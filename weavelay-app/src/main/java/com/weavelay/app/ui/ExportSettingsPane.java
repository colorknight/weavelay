package com.weavelay.app.ui;

import com.weavelay.app.AppPreferences;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 导出设置: 导出目录等.
 */
public final class ExportSettingsPane {

    private final VBox root = new VBox(16);
    private final AppPreferences preferences;
    private final Window ownerWindow;
    private final TextField exportDirField = new TextField();

    public ExportSettingsPane(AppPreferences preferences, Window ownerWindow) {
        this.preferences = preferences;
        this.ownerWindow = ownerWindow;
        build();
        load();
    }

    VBox getRoot() {
        return root;
    }

    void save() {
        String text = exportDirField.getText();
        if (text != null && !text.trim().isEmpty()) {
            preferences.setExportDir(Paths.get(text.trim()));
        } else {
            preferences.setExportDir(null);
        }
    }

    private void build() {
        root.getStyleClass().add("settings-form-pane");
        root.setPadding(new Insets(12, 16, 16, 16));

        Label title = new Label("导出设置");
        title.getStyleClass().add("settings-section-title");

        Label intro = new Label("应用模式点「写出Excel」时写入此目录。"
                + "留空则每次手动选；选过一次后会记住。");
        intro.getStyleClass().add("settings-hint");
        intro.setWrapText(true);
        intro.setMaxWidth(Double.MAX_VALUE);

        VBox form = new VBox(16);
        form.getStyleClass().add("settings-ocr-form");
        form.setPadding(new Insets(8, 0, 0, 0));

        Label nameLabel = new Label("导出目录");
        nameLabel.getStyleClass().add("settings-field-label");
        nameLabel.setMinWidth(130);
        exportDirField.setPromptText("留空则每次选择");
        exportDirField.setPrefWidth(420);

        Button browseBtn = new Button("浏览...");
        browseBtn.getStyleClass().add("settings-toolbar-btn");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择导出目录");
            String cur = exportDirField.getText();
            if (cur != null && !cur.trim().isEmpty()) {
                File f = new File(cur.trim());
                if (f.isDirectory()) dc.setInitialDirectory(f);
            }
            File dir = dc.showDialog(ownerWindow);
            if (dir != null) exportDirField.setText(dir.getAbsolutePath());
        });

        HBox inputRow = new HBox(12, nameLabel, exportDirField, browseBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        Label help = new Label("文件名：{文件类型}-{PDF名}.xlsx。例如 工艺规程-零件图.xlsx。"
                + "内容按该文件类型绑定的 Excel 模板填充，需先确认各页。");
        help.getStyleClass().add("settings-field-help");
        help.setWrapText(true);
        help.setMaxWidth(Double.MAX_VALUE);

        VBox block = new VBox(6, inputRow, help);
        block.getStyleClass().add("settings-ocr-param");
        form.getChildren().add(block);

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().add("settings-toolbar-btn");
        saveBtn.setOnAction(e -> {
            save();
        });

        Button resetBtn = new Button("清空");
        resetBtn.getStyleClass().add("settings-toolbar-btn");
        resetBtn.setOnAction(e -> {
            exportDirField.setText("");
            preferences.setExportDir(null);
        });

        HBox buttons = new HBox(10, saveBtn, resetBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(title, intro, form, buttons);
    }

    private void load() {
        Path dir = preferences.getExportDir();
        exportDirField.setText(dir != null ? dir.toAbsolutePath().toString() : "");
    }
}
