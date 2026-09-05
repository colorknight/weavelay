package com.weavelay.app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 设置窗口内弹窗统一样式（白底、去掉系统问号大图、按钮对齐主题色）。
 */
final class SettingsDialogs {

    private SettingsDialogs() {}

    static void style(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        DialogPane pane = dialog.getDialogPane();
        String css = SettingsWindow.class.getResource("settings.css").toExternalForm();
        if (!pane.getStylesheets().contains(css)) {
            pane.getStylesheets().add(css);
        }
        if (!pane.getStyleClass().contains("settings-dialog")) {
            pane.getStyleClass().add("settings-dialog");
        }
        dialog.setGraphic(null);
        pane.setGraphic(null);
        // 按钮样式：确定/主操作为强调色
        pane.getButtonTypes().forEach(type -> {
            Button btn = (Button) pane.lookupButton(type);
            if (btn == null) {
                return;
            }
            btn.getStyleClass().add("settings-dialog-btn");
            ButtonBar.ButtonData data = type.getButtonData();
            if (data == ButtonBar.ButtonData.OK_DONE
                    || data == ButtonBar.ButtonData.YES
                    || data == ButtonBar.ButtonData.APPLY) {
                btn.getStyleClass().add("settings-dialog-btn-primary");
            } else if (data == ButtonBar.ButtonData.CANCEL_CLOSE
                    || data == ButtonBar.ButtonData.NO) {
                btn.getStyleClass().add("settings-dialog-btn-muted");
            }
        });
    }

    static void showInfo(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message == null ? "" : message);
        style(alert);
        alert.showAndWait();
    }

    static void showHelp(Window owner, String title, String body) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(title == null || title.isBlank() ? "说明" : title);
        alert.setHeaderText(null);
        alert.getButtonTypes().setAll(new ButtonType("关闭", ButtonBar.ButtonData.OK_DONE));

        TextArea area = new TextArea(body == null ? "" : body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefWidth(620);
        area.setPrefHeight(480);
        area.getStyleClass().add("settings-form-textarea");
        VBox.setVgrow(area, Priority.ALWAYS);

        VBox box = new VBox(area);
        box.setPadding(new Insets(4, 0, 0, 0));
        alert.getDialogPane().setContent(box);
        alert.getDialogPane().setPrefWidth(660);
        style(alert);
        alert.showAndWait();
    }

    static void showError(Window owner, String title, Exception ex) {
        String msg = ex == null || ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage();
        showError(owner, title, msg);
    }

    static void showError(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(title == null || title.isBlank() ? "错误" : title);
        alert.setHeaderText(null);
        alert.setContentText(message == null ? "" : message);
        style(alert);
        alert.showAndWait();
    }

    /** @return true = 确定 */
    static boolean confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(title == null ? "确认" : title);
        alert.setHeaderText(null);
        alert.setContentText(message == null ? "" : message);
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        style(alert);
        Optional<ButtonType> r = alert.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    static Optional<ButtonType> choose(
            Window owner,
            String title,
            String heading,
            String detail,
            ButtonType... types) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(owner);
        alert.setTitle(title == null ? "" : title);
        alert.setHeaderText(null);
        alert.getButtonTypes().setAll(types);

        VBox body = new VBox(10);
        body.setPadding(new Insets(4, 0, 0, 0));
        if (heading != null && !heading.isBlank()) {
            Label h = new Label(heading);
            h.getStyleClass().add("settings-dialog-heading");
            h.setWrapText(true);
            body.getChildren().add(h);
        }
        if (detail != null && !detail.isBlank()) {
            Label d = new Label(detail);
            d.getStyleClass().add("settings-dialog-detail");
            d.setWrapText(true);
            d.setMaxWidth(420);
            body.getChildren().add(d);
        }
        alert.getDialogPane().setContent(body);
        style(alert);
        // 自定义按钮：第一个非取消视为主按钮
        for (ButtonType type : types) {
            Button btn = (Button) alert.getDialogPane().lookupButton(type);
            if (btn == null) {
                continue;
            }
            if (type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) {
                btn.getStyleClass().add("settings-dialog-btn-muted");
            } else if (!btn.getStyleClass().contains("settings-dialog-btn-primary")) {
                // 更换 等自定义类型
                if (type == types[0]) {
                    btn.getStyleClass().add("settings-dialog-btn-primary");
                }
            }
        }
        return alert.showAndWait();
    }

    /** 输出模板：更换 / 清除 / 取消。 */
    enum TemplateAction { REPLACE, CLEAR, CANCEL }

    static TemplateAction chooseExcelTemplateAction(
            Window owner, String familyName, String currentPath) {
        ButtonType replace = new ButtonType("更换模板", ButtonBar.ButtonData.YES);
        ButtonType clear = new ButtonType("清除绑定", ButtonBar.ButtonData.LEFT);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(owner);
        alert.setTitle("输出模板");
        alert.setHeaderText(null);
        alert.getButtonTypes().setAll(clear, cancel, replace);

        Path pathObj = null;
        if (currentPath != null && !currentPath.isBlank()) {
            try {
                pathObj = java.nio.file.Path.of(currentPath.trim());
            } catch (Exception ignored) {
            }
        }
        String fileName = pathObj != null && pathObj.getFileName() != null
                ? pathObj.getFileName().toString()
                : (currentPath == null || currentPath.isBlank() ? "（未绑定）" : currentPath);
        String dirText = "";
        if (pathObj != null && pathObj.getParent() != null) {
            dirText = pathObj.getParent().toString();
        }

        Label eyebrow = new Label("输出模板");
        eyebrow.getStyleClass().add("settings-dialog-eyebrow");

        Label title = new Label(familyName == null || familyName.isBlank() ? "当前文件类型" : familyName);
        title.getStyleClass().add("settings-dialog-heading");
        title.setWrapText(true);

        Label fileLabel = new Label(fileName);
        fileLabel.getStyleClass().add("settings-dialog-filename");
        fileLabel.setWrapText(true);
        fileLabel.setMaxWidth(420);

        Label dirLabel = new Label(dirText.isEmpty() ? (currentPath == null ? "" : currentPath) : dirText);
        dirLabel.getStyleClass().add("settings-dialog-dir");
        dirLabel.setWrapText(true);
        dirLabel.setMaxWidth(420);

        VBox pathCard = new VBox(4, fileLabel);
        if (!dirLabel.getText().isBlank()) {
            pathCard.getChildren().add(dirLabel);
        }
        pathCard.getStyleClass().add("settings-dialog-path-card");
        pathCard.setMaxWidth(440);

        Label ask = new Label("可更换为其他 .xlsx，或清除后重新绑定。");
        ask.getStyleClass().add("settings-dialog-detail");
        ask.setWrapText(true);

        VBox body = new VBox(10, eyebrow, title, pathCard, ask);
        body.setAlignment(Pos.TOP_LEFT);
        body.setPadding(new Insets(6, 4, 2, 4));
        alert.getDialogPane().setContent(body);
        alert.getDialogPane().setPrefWidth(480);
        alert.getDialogPane().getStyleClass().add("settings-template-dialog");
        style(alert);

        Button replaceBtn = (Button) alert.getDialogPane().lookupButton(replace);
        if (replaceBtn != null) {
            replaceBtn.getStyleClass().add("settings-dialog-btn-primary");
            replaceBtn.setDefaultButton(true);
        }
        Button clearBtn = (Button) alert.getDialogPane().lookupButton(clear);
        if (clearBtn != null) {
            clearBtn.getStyleClass().add("settings-dialog-btn-danger");
        }
        Button cancelBtn = (Button) alert.getDialogPane().lookupButton(cancel);
        if (cancelBtn != null) {
            cancelBtn.getStyleClass().add("settings-dialog-btn-muted");
        }

        Optional<ButtonType> r = alert.showAndWait();
        if (r.isEmpty() || r.get() == cancel) {
            return TemplateAction.CANCEL;
        }
        if (r.get() == clear) {
            return TemplateAction.CLEAR;
        }
        return TemplateAction.REPLACE;
    }

    static Optional<String> editMultiline(
            Window owner, String title, String heading, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial == null ? "" : initial);
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(null);

        TextArea area = new TextArea(initial == null ? "" : initial);
        area.getStyleClass().add("settings-form-textarea");
        area.setPrefRowCount(8);
        area.setPrefWidth(420);
        area.setWrapText(true);
        VBox.setVgrow(area, Priority.ALWAYS);

        Label h = new Label(heading == null ? "" : heading);
        h.getStyleClass().add("settings-dialog-heading");
        h.setWrapText(true);
        Label tip = new Label("每行一条规则");
        tip.getStyleClass().add("settings-dialog-detail");

        VBox body = new VBox(10, h, tip, area);
        body.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setPrefWidth(480);
        style(dialog);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return area.getText();
            }
            return null;
        });
        return dialog.showAndWait();
    }

    static void styleDialog(Dialog<?> dialog) {
        style(dialog);
    }
}
