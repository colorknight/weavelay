package com.weavelay.app.ui;

import com.weavelay.app.AppPreferences;
import com.weavelay.core.store.WeaveLayRuntime;
import com.weavelay.ocr.RapidOcrService;
import javafx.scene.control.Button;
import javafx.stage.Window;

/**
 * 右上角设置齿轮按钮.
 */
public final class SettingsButtonFactory {

    private SettingsButtonFactory() {
    }

    public static Button create(
            WeaveLayRuntime runtime,
            AppPreferences preferences,
            RapidOcrService ocrService,
            com.weavelay.core.license.LicenseService licenseService,
            Runnable onSaved) {
        Button settings = AppIcons.iconButton(AppIcons.Kind.GEAR, "设置", false);
        settings.setMinSize(28, 28);
        settings.setPrefSize(28, 28);
        settings.setMaxSize(28, 28);
        settings.getStyleClass().remove("icon-action-btn");
        settings.getStyleClass().add("icon-header-btn");
        settings.setOnAction(e -> {
            if (runtime == null) {
                return;
            }
            Window owner = settings.getScene() == null ? null : settings.getScene().getWindow();
            SettingsWindow.open(owner, runtime, preferences, ocrService, licenseService, onSaved);
        });
        return settings;
    }
}
