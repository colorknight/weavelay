package com.weavelay.app.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/** 输出配置对话框: 定义字段名和类型. */
public final class OutputSettingsDialog {

    public static final class FieldDef {
        public String name;
        public String type; // "string" or "list"
        public String columnHeaders; // comma-separated, only for list

        public FieldDef(String name, String type, String columnHeaders) {
            this.name = name;
            this.type = type;
            this.columnHeaders = columnHeaders;
        }
    }

    public static List<FieldDef> show(Window owner, List<FieldDef> current) {
        List<FieldDef> fields = new ArrayList<>(current == null ? List.of() : current);
        if (fields.isEmpty()) {
            fields.add(new FieldDef("", "string", ""));
        }

        VBox list = new VBox(6);
        list.setPadding(new Insets(8, 0, 8, 0));

        Runnable[] rebuildHolder = new Runnable[1];
        rebuildHolder[0] = () -> {
            Runnable self = rebuildHolder[0];
            list.getChildren().clear();
            for (int i = 0; i < fields.size(); i++) {
                int idx = i;
                FieldDef f = fields.get(i);

                TextField nameField = new TextField(f.name);
                nameField.setPromptText("字段名");
                nameField.setPrefWidth(120);
                nameField.textProperty().addListener((obs, o, n) -> f.name = n);

                ComboBox<String> typeCombo = new ComboBox<>();
                typeCombo.getItems().addAll("字符串", "列表");
                typeCombo.setValue("字符串".equals(f.type) ? "字符串" : "列表");
                typeCombo.setPrefWidth(80);
                typeCombo.valueProperty().addListener((obs, o, n) -> f.type = n);

                TextField headersField = new TextField(f.columnHeaders);
                headersField.setPromptText("列头(逗号分隔)");
                headersField.setPrefWidth(140);
                headersField.setVisible("列表".equals(f.type));
                headersField.textProperty().addListener((obs, o, n) -> f.columnHeaders = n);
                typeCombo.valueProperty().addListener((obs, o, n) ->
                        headersField.setVisible("列表".equals(n)));

                Button delBtn = new Button("×");
                delBtn.getStyleClass().add("app-btn");
                delBtn.setOnAction(e -> {
                    fields.remove(idx);
                    self.run();
                });

                HBox row = new HBox(6, nameField, typeCombo, headersField, delBtn);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                list.getChildren().add(row);
            }

            Button addBtn = new Button("+ 添加字段");
            addBtn.getStyleClass().add("app-btn");
            addBtn.setOnAction(e -> {
                fields.add(new FieldDef("", "string", ""));
                self.run();
            });
            list.getChildren().add(addBtn);
        };
        rebuildHolder[0].run();

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("设置输出");

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().add("app-btn-primary");
        saveBtn.setOnAction(e -> stage.close());

        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("app-btn");
        cancelBtn.setOnAction(e -> {
            fields.clear();
            stage.close();
        });

        HBox buttons = new HBox(8, saveBtn, cancelBtn);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox root = new VBox(10, new Label("定义本文件族的输出字段:"), list, buttons);
        root.setPadding(new Insets(16));
        VBox.setVgrow(list, Priority.ALWAYS);

        Scene scene = new Scene(root, 560, 400);
        scene.getStylesheets().add(OutputSettingsDialog.class.getResource("app.css").toExternalForm());
        stage.setScene(scene);
        stage.showAndWait();

        fields.removeIf(f -> f.name == null || f.name.trim().isEmpty());
        return fields.isEmpty() ? null : fields;
    }
}
