package com.weavelay.app.ui;

import com.weavelay.core.store.FamilyRecord;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 文件类型新建表单 (嵌入设置窗口右侧).
 */
final class FamilyAddFormPane {

    private final VBox root = new VBox();
    private final TextField codeField = formField("内部 code，如 process_spec");
    private final TextField nameField = formField("名称，如 工艺规程");
    private final TextField englishField = formField("英文，如 Process Specification");
    private final TextArea descField = new TextArea();

    FamilyAddFormPane(Runnable onBack, Runnable onConfirm) {
        Label heading = new Label("新增文件类型");
        heading.getStyleClass().add("settings-form-heading");
        Label subtitle = new Label("填写文件类型基本信息。代码用于内部识别，名称与英文将显示在界面中。");
        subtitle.getStyleClass().add("settings-form-subtitle");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, heading, subtitle);
        header.getStyleClass().add("settings-form-header");

        descField.setPromptText("描述该文件类型的用途或适用范围");
        descField.setPrefRowCount(4);
        descField.setWrapText(true);
        descField.getStyleClass().add("settings-form-textarea");

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(14);
        grid.setPadding(new Insets(24, 28, 16, 28));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(56);
        labelCol.setPrefWidth(56);
        labelCol.setHalignment(HPos.RIGHT);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        addRow(grid, 0, "代码", codeField);
        addRow(grid, 1, "名称", nameField);
        addRow(grid, 2, "英文", englishField);
        addRow(grid, 3, "描述", descField);

        VBox formBody = new VBox(grid);
        formBody.getStyleClass().add("settings-form-body");
        VBox.setVgrow(formBody, Priority.ALWAYS);

        javafx.scene.control.Button backBtn = AppIcons.iconButton(AppIcons.Kind.BACK, "返回列表", true);
        backBtn.setOnAction(e -> onBack.run());
        javafx.scene.control.Button confirmBtn = AppIcons.iconButton(AppIcons.Kind.CONFIRM, "保存", true);
        confirmBtn.setOnAction(e -> onConfirm.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, backBtn, spacer, confirmBtn);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("settings-form-footer");
        footer.setPadding(new Insets(12, 28, 20, 28));

        root.getChildren().addAll(header, formBody, footer);
        root.getStyleClass().add("settings-form-panel");
        VBox.setVgrow(root, Priority.ALWAYS);
    }

    VBox getRoot() {
        return root;
    }

    void clear() {
        codeField.clear();
        nameField.clear();
        englishField.clear();
        descField.clear();
    }

    FamilyRecord readRecord() {
        String code = codeField.getText().trim();
        String name = nameField.getText().trim();
        String english = englishField.getText().trim();
        if (code.isEmpty() || name.isEmpty() || english.isEmpty()) {
            return null;
        }
        return new FamilyRecord(code, name, english, descField.getText().trim());
    }

    private static TextField formField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("settings-form-field");
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    private static void addRow(GridPane grid, int row, String labelText, javafx.scene.Node field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("settings-form-label");
        label.setAlignment(Pos.TOP_RIGHT);
        GridPane.setValignment(label, VPos.TOP);
        if (field instanceof TextArea) {
            GridPane.setValignment(field, VPos.TOP);
        }
        grid.add(label, 0, row);
        grid.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
    }
}
