package com.weavelay.app.ui;

import com.weavelay.core.license.LicenseDocument;
import com.weavelay.core.license.LicenseService;
import com.weavelay.core.license.LicenseStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * Licence：状态、机器指纹、导入授权文件。
 */
public final class LicenseSettingsPane {

    private final VBox root = new VBox(14);
    private final LicenseService licenseService;
    private final Window ownerWindow;
    private final Runnable onChanged;

    private final Label statusLabel = new Label();
    private final TextArea machineIdArea = new TextArea();
    private final Label detailLabel = new Label();

    public LicenseSettingsPane(LicenseService licenseService, Window ownerWindow, Runnable onChanged) {
        this.licenseService = licenseService;
        this.ownerWindow = ownerWindow;
        this.onChanged = onChanged;
        build();
        reload();
    }

    VBox getRoot() {
        return root;
    }

    void reload() {
        LicenseStatus st = licenseService.refresh();
        statusLabel.setText(kindLabel(st));
        statusLabel.getStyleClass().removeAll(
                "license-status-ok", "license-status-bad", "license-status-warn");
        if (st.isUsable()) {
            statusLabel.getStyleClass().add("license-status-ok");
        } else if (st.getKind() == LicenseStatus.Kind.MISSING) {
            statusLabel.getStyleClass().add("license-status-warn");
        } else {
            statusLabel.getStyleClass().add("license-status-bad");
        }
        machineIdArea.setText(st.getMachineId());
        StringBuilder detail = new StringBuilder(st.getMessage());
        LicenseDocument doc = st.getDocument();
        if (doc != null) {
            detail.append("\nlicenceId: ").append(doc.getLicenseId());
            if (!doc.getCustomer().isBlank()) {
                detail.append("\n客户: ").append(doc.getCustomer());
            }
            if (!doc.getDealerId().isBlank()) {
                detail.append("\n渠道: ").append(doc.getDealerId());
            }
            if (!doc.isPerpetual()) {
                String until = doc.expiresLocalDate();
                detail.append("\n到期: ").append(until.isBlank() ? doc.getExpiresAt() : until);
            } else if (st.getKind() == LicenseStatus.Kind.VALID
                    || st.getKind() == LicenseStatus.Kind.BYPASS) {
                detail.append("\n期限: 永久");
            }
            if (!doc.getFeatures().isEmpty()) {
                detail.append("\n功能: ").append(String.join(", ", doc.getFeatures()));
            }
        }
        detail.append("\n文件: ").append(licenseService.getStore().getLicensePath());
        detailLabel.setText(detail.toString());
    }

    private void build() {
        root.getStyleClass().add("settings-form-pane");
        root.setPadding(new Insets(12, 16, 16, 16));

        Label title = new Label("Licence 授权");
        title.getStyleClass().add("settings-section-title");

        Button importBtn = new Button("导入 licence 文件…");
        importBtn.getStyleClass().addAll("settings-toolbar-btn", "settings-dialog-btn-primary");
        importBtn.setOnAction(e -> importLicense());
        Button refreshBtn = new Button("刷新状态");
        refreshBtn.getStyleClass().add("settings-toolbar-btn");
        refreshBtn.setOnAction(e -> reload());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleRow = new HBox(10, title, spacer, importBtn, refreshBtn);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label intro = new Label(
                "1. 复制「机器指纹」发给厂商 → 2. 收到 .weavelaylic 后点右上角「导入」。"
                        + "授权绑本机，拷到其他电脑无效。");
        intro.getStyleClass().add("settings-hint");
        intro.setWrapText(true);
        intro.setMaxWidth(Double.MAX_VALUE);

        Label statusTitle = new Label("当前状态");
        statusTitle.getStyleClass().add("settings-field-label");
        statusLabel.getStyleClass().add("license-status-label");
        statusLabel.setWrapText(true);

        Label midTitle = new Label("机器指纹（发给厂商签发用）");
        midTitle.getStyleClass().add("settings-field-label");
        machineIdArea.setEditable(false);
        machineIdArea.setWrapText(true);
        machineIdArea.setPrefRowCount(3);
        machineIdArea.getStyleClass().add("settings-form-textarea");
        Button copyMid = new Button("复制指纹");
        copyMid.getStyleClass().add("settings-toolbar-btn");
        copyMid.setOnAction(e -> copyText(machineIdArea.getText()));

        detailLabel.getStyleClass().add("settings-hint");
        detailLabel.setWrapText(true);

        root.getChildren().addAll(
                titleRow, intro,
                statusTitle, statusLabel,
                midTitle, machineIdArea, copyMid,
                detailLabel);
    }

    private void importLicense() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入 WeaveLay licence");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        "Licence (*" + LicenseDocument.FILE_EXTENSION + ")",
                        "*" + LicenseDocument.FILE_EXTENSION),
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File picked = chooser.showOpenDialog(ownerWindow);
        if (picked == null) {
            return;
        }
        try {
            LicenseStatus st = licenseService.importLicense(picked.toPath());
            reload();
            if (onChanged != null) {
                onChanged.run();
            }
            if (st.isUsable()) {
                SettingsDialogs.showInfo(ownerWindow, "导入成功：\n" + st.getMessage());
            } else {
                SettingsDialogs.showInfo(ownerWindow, "已导入但仍未生效：\n" + st.getMessage());
            }
        } catch (Exception ex) {
            SettingsDialogs.showError(ownerWindow, "导入 licence 失败", ex);
        }
    }

    private void copyText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.trim());
        Clipboard.getSystemClipboard().setContent(content);
        SettingsDialogs.showInfo(ownerWindow, "已复制到剪贴板");
    }

    private static String kindLabel(LicenseStatus st) {
        return switch (st.getKind()) {
            case VALID -> "已授权";
            case BYPASS -> "开发旁路";
            case MISSING -> "未授权";
            case INVALID_SIG -> "签名无效";
            case MACHINE_MISMATCH -> "机器不匹配";
            case EXPIRED -> "已过期";
            case WRONG_PRODUCT -> "产品不匹配";
        };
    }
}
