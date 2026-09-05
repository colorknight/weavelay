package com.weavelay.app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * 工业场景常用符号点选：给业务人员改 OCR 结果时用，避免手敲 ⌀±° 等。
 * <p>全局快捷键 {@link #HOTKEY}（F4）：在任意已安装的 Scene 上，对当前焦点输入框弹出本面板。
 */
public final class IndustrialSymbolPicker {

    /** 打开特殊键盘的快捷键（F1–F12 中的 F4）。 */
    public static final KeyCode HOTKEY = KeyCode.F4;

    private record Sym(String ch, String tip) {}

    private static final Sym[] SYMBOLS = {
            new Sym("⌀", "直径"),
            new Sym("∅", "直径异体/空集"),
            new Sym("±", "正负公差"),
            new Sym("°", "度"),
            new Sym("℃", "摄氏度"),
            new Sym("μ", "微 (μm)"),
            new Sym("Ω", "欧姆"),
            new Sym("≤", "小于等于"),
            new Sym("≥", "大于等于"),
            new Sym("≈", "约等于"),
            new Sym("×", "乘号"),
            new Sym("÷", "除号"),
            new Sym("·", "中间点"),
            new Sym("⊥", "垂直"),
            new Sym("∠", "角度"),
            new Sym("φ", "phi / 小直径"),
            new Sym("Φ", "Phi / 大直径"),
            new Sym("α", "alpha"),
            new Sym("β", "beta"),
            new Sym("γ", "gamma"),
            new Sym("δ", "delta"),
            new Sym("θ", "theta"),
            new Sym("¹", "上标1"),
            new Sym("²", "上标2"),
            new Sym("³", "上标3"),
            new Sym("₀", "下标0"),
            new Sym("₁", "下标1"),
            new Sym("₂", "下标2"),
            new Sym("‰", "千分号"),
            new Sym("—", "长横线"),
            new Sym("–", "短横线"),
            new Sym("～", "全角波浪"),
    };

    private IndustrialSymbolPicker() {}

    public static String hotkeyLabel() {
        return HOTKEY.getName(); // "F4"
    }

    /**
     * 在 Scene 上安装 F4：当前焦点在可编辑输入框时弹出特殊键盘。
     * 主窗口、表格编辑弹窗都应调用一次。
     */
    public static void installSceneHotkey(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != HOTKEY) {
                return;
            }
            if (tryShowForFocus(scene, null)) {
                e.consume();
            }
        });
    }

    /** 尝试对当前焦点输入框弹出；失败返回 false。 */
    public static boolean tryShowForFocus(Scene scene, TextInputControl fallback) {
        TextInputControl field = findEditableFocus(scene);
        if (field == null) {
            field = fallback;
        }
        if (field == null || !field.isEditable() || field.isDisabled()) {
            return false;
        }
        final TextInputControl target = field;
        show(target, target, target::requestFocus);
        return true;
    }

    private static TextInputControl findEditableFocus(Scene scene) {
        if (scene == null) {
            return null;
        }
        Node n = scene.getFocusOwner();
        while (n != null) {
            if (n instanceof TextInputControl tic && tic.isEditable() && !tic.isDisabled()) {
                return tic;
            }
            n = n.getParent();
        }
        return null;
    }

    /** 生成「符号」按钮，弹出点选面板并向 target 光标处插入。 */
    public static Button createButton(TextInputControl target) {
        return createButton(target, null);
    }

    public static Button createButton(TextInputControl target, Runnable afterInsert) {
        Button btn = new Button("符号");
        btn.getStyleClass().add("app-btn");
        btn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8;");
        btn.setFocusTraversable(false);
        btn.setTooltip(new Tooltip("特殊键盘  " + hotkeyLabel()));
        btn.setOnAction(e -> show(btn, target, afterInsert));
        return btn;
    }

    public static void show(Node anchor, TextInputControl target) {
        show(anchor, target, null);
    }

    public static void show(Node anchor, TextInputControl target, Runnable afterInsert) {
        if (target == null) {
            return;
        }
        if (anchor == null) {
            anchor = target;
        }
        Popup popup = new Popup();
        popup.setAutoHide(true);

        Label title = new Label("点击插入到光标处（快捷键 " + hotkeyLabel() + "）");
        title.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        FlowPane flow = new FlowPane(6, 6);
        flow.setPrefWrapLength(280);
        for (Sym sym : SYMBOLS) {
            Button b = new Button(sym.ch);
            b.setMinSize(34, 28);
            b.setPrefSize(34, 28);
            b.setStyle("-fx-font-size: 14px; -fx-cursor: hand;");
            b.setTooltip(new Tooltip(sym.tip + "  (" + sym.ch + ")"));
            b.setFocusTraversable(false);
            b.setOnAction(ev -> {
                insertAtCaret(target, sym.ch);
                popup.hide();
                target.requestFocus();
                if (afterInsert != null) {
                    afterInsert.run();
                }
            });
            flow.getChildren().add(b);
        }

        VBox box = new VBox(8, title, flow);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #ffffff; -fx-border-color: #b8d4d4;"
                + " -fx-border-width: 1; -fx-background-radius: 6; -fx-border-radius: 6;");
        popup.getContent().add(box);

        javafx.stage.Window owner = target.getScene() != null ? target.getScene().getWindow() : null;
        var fieldBounds = target.localToScreen(target.getBoundsInLocal());
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();

        double seedX = fieldBounds != null ? fieldBounds.getMinX() : screen.getMinX() + 40;
        double seedY = fieldBounds != null ? fieldBounds.getMaxY() + 6 : screen.getMinY() + 80;
        if (owner != null) {
            popup.show(owner, seedX, seedY);
        } else {
            popup.show(anchor, seedX, seedY);
        }
        double popupW = Math.max(280, popup.getWidth());
        double popupH = Math.max(120, popup.getHeight());
        double x = seedX;
        double y = seedY;
        if (fieldBounds != null) {
            x = fieldBounds.getMinX();
            y = fieldBounds.getMaxY() + 6;
            if (y + popupH > screen.getMaxY() - 8) {
                y = fieldBounds.getMinY() - popupH - 6;
            }
        }
        if (x + popupW > screen.getMaxX() - 8) {
            x = screen.getMaxX() - popupW - 8;
        }
        if (x < screen.getMinX() + 8) {
            x = screen.getMinX() + 8;
        }
        if (y < screen.getMinY() + 8) {
            y = screen.getMinY() + 8;
        }
        popup.setX(x);
        popup.setY(y);
    }

    public static void insertAtCaret(TextInputControl field, String text) {
        if (field == null || text == null || text.isEmpty()) {
            return;
        }
        String cur = field.getText() == null ? "" : field.getText();
        int start = Math.max(0, field.getSelection().getStart());
        int end = Math.max(start, field.getSelection().getEnd());
        if (field.getSelectedText().isEmpty()) {
            int caret = field.getCaretPosition();
            if (caret < 0 || caret > cur.length()) {
                caret = cur.length();
            }
            start = caret;
            end = caret;
        }
        String next = cur.substring(0, start) + text + cur.substring(end);
        field.setText(next);
        int pos = start + text.length();
        field.positionCaret(pos);
    }

    /** 把符号按钮挂到输入框右侧，组成一行。 */
    public static javafx.scene.layout.HBox wrapWithButton(TextInputControl target, Runnable afterInsert) {
        Button btn = createButton(target, afterInsert);
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(4, target, btn);
        row.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.HBox.setHgrow(target, javafx.scene.layout.Priority.ALWAYS);
        return row;
    }
}
