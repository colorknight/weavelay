package com.weavelay.app.ui;

import com.weavelay.core.store.FamilyRecord;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;

/**
 * 文件类型 (顶栏) 下拉框.
 */
public final class PageKindPickerBar {

    public interface Listener {
        void onDocumentFamilyChanged(FamilyRecord family);
    }

    private static final double FAMILY_COMBO_WIDTH = 200.0;

    private final ComboBox<FamilyRecord> familyCombo = new ComboBox<FamilyRecord>();
    private final HBox familyBar = new HBox(6);

    private Listener listener;
    private boolean suppressFamilyEvent;

    public PageKindPickerBar() {
        familyCombo.setPromptText("文件类型");
        familyCombo.setPrefWidth(FAMILY_COMBO_WIDTH);
        familyCombo.setMinWidth(FAMILY_COMBO_WIDTH);
        familyCombo.setMaxWidth(280);
        familyCombo.getStyleClass().add("app-combo");

        familyCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressFamilyEvent || listener == null || newVal == null || newVal.equals(oldVal)) {
                return;
            }
            listener.onDocumentFamilyChanged(newVal);
        });

        familyBar.setAlignment(Pos.CENTER_LEFT);
        familyBar.getChildren().add(familyCombo);
    }

    public ComboBox<FamilyRecord> getFamilyCombo() {
        return familyCombo;
    }

    public Node getFamilyBar() {
        return familyBar;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setFamilies(java.util.List<FamilyRecord> families, FamilyRecord selected) {
        suppressFamilyEvent = true;
        try {
            familyCombo.getItems().setAll(families);
            familyCombo.setValue(selected);
        } finally {
            suppressFamilyEvent = false;
        }
    }
}
