package com.weavelay.app.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * 简易二态开关（JavaFX 17 无内置 Switch）。
 * selected=false → 模板；selected=true → 应用。
 */
public final class ModeSwitch extends StackPane {

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Rectangle track = new Rectangle(40, 22);
    private final Circle thumb = new Circle(8);

    public ModeSwitch() {
        getStyleClass().add("mode-switch");
        track.getStyleClass().add("mode-switch-track");
        thumb.getStyleClass().add("mode-switch-thumb");
        track.setArcWidth(22);
        track.setArcHeight(22);
        setMaxSize(40, 22);
        setPrefSize(40, 22);
        setPadding(new Insets(2));
        setCursor(Cursor.HAND);
        getChildren().addAll(track, thumb);
        setAlignment(Pos.CENTER_LEFT);
        selected.addListener((obs, o, on) -> applyVisual(on));
        setOnMouseClicked(e -> setSelected(!isSelected()));
        applyVisual(false);
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    private void applyVisual(boolean on) {
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), on);
        setAlignment(on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
    }
}
