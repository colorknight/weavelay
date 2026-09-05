package com.weavelay.app.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * 可选中复制的文本组件。基于 TextField，消除所有输入框痕迹，
 * 外观与 Label 完全一致，支持鼠标划选 + Ctrl+C + 右键复制。
 */
public final class SelectableLabel extends TextField {

    private static final String BASE_STYLE =
            "-fx-background-color: transparent; "
            + "-fx-background-insets: 0; "
            + "-fx-background-radius: 0; "
            + "-fx-border-color: transparent; "
            + "-fx-border-width: 0; "
            + "-fx-padding: 0; "
            + "-fx-focus-color: transparent; "
            + "-fx-faint-focus-color: transparent; "
            + "-fx-text-box-border: transparent; "
            + "-fx-highlight-fill: #3399FF; "
            + "-fx-highlight-text-fill: white; ";

    public SelectableLabel(String text) {
        super(text);
        setEditable(false);
        setFocusTraversable(false);
        setMouseTransparent(false);
        setPrefColumnCount(0);

        // 隐藏光标
        setStyle(BASE_STYLE);
        skinProperty().addListener((obs, old, newSkin) -> {
            if (newSkin != null) {
                try {
                    // 反射隐藏 caret
                    var caretField = newSkin.getClass().getDeclaredField("caret");
                    caretField.setAccessible(true);
                    caretField.set(newSkin, null);
                } catch (Exception ignored) {}
            }
        });

        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            String t = getText();
            if (t != null) content.putString(t);
            clipboard.setContent(content);
        });
        ContextMenu menu = new ContextMenu(copyItem);
        setContextMenu(menu);
    }

    public SelectableLabel(String text, String extraStyle) {
        this(text);
        if (extraStyle != null && !extraStyle.isEmpty()) {
            setStyle(BASE_STYLE + extraStyle);
        }
    }
}
