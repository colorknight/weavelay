package com.weavelay.app.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * 内联 SVG 图标 (无第三方 icon 库).
 */
public final class AppIcons {

    private static final double VIEW = 24.0;

    private AppIcons() {
    }

    public enum Kind {
        ADD("M19,13H13V19H11V13H5V11H11V5H13V11H19V13Z", "#2a8f85"),
        PAGE_TYPES("M3,13H5V11H3V13M3,17H5V15H3V17M3,9H5V7H3V9M7,13H21V11H7V13M7,17H21V15H7V17M7,7V9H21V7H7Z", "#2a8f85"),
        CONFIRM("M9,16.17L4.83,12L3.41,13.41L9,19L21,7L19.59,5.59L9,16.17Z", "#2a8f85"),
        BACK("M20,11V13H8L13.5,18.5L12.08,19.92L4.16,12L12.08,4.08L13.5,5.5L8,11H20Z", "#5f7f7f"),
        DELETE("M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6V19M8,9h8v10H8V9M15.5,4l-1,-1h-5l-1,1H5v2h14V4H15.5Z", "#c62828"),
        MOVE_UP("M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6 1.41,1.41Z", "#5f7f7f"),
        MOVE_DOWN("M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41Z", "#5f7f7f"),
        OUTPUT("M14,2H6A2,2 0 0,0 4,4V20A2,2 0 0,0 6,22H18A2,2 0 0,0 20,20V8L14,2M18,20H6V4H13V9H18V20M8,12V14H16V12H8M8,16V18H13V16H8Z", "#2a8f85"),
        /** Excel 输出模板（表格） */
        TEMPLATE("M3,3H21V21H3V3M5,5V8H8V5H5M10,5V8H14V5H10M16,5V8H19V5H16M5,10V14H8V10H5M10,10V14H14V10H10M16,10V14H19V10H16M5,16V19H8V16H5M10,16V19H14V16H10M16,16V19H19V16H16Z", "#2a8f85"),
        /** 导出模板包（下载） */
        EXPORT("M5,20H19V18H5M19,9H15V3H9V9H5L12,16L19,9Z", "#2a8f85"),
        /** 导入模板包（上传） */
        IMPORT("M9,16V10H5L12,3L19,10H15V16H9M5,20H19V18H5V20Z", "#2a8f85"),
        /** 模板规则（名称+正则） */
        RULES("M3,5H21V7H3V5M3,11H21V13H3V11M3,17H15V19H3V17Z", "#2a8f85"),
        REFRESH("M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4c-3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35Z", "#5f7f7f"),
        GEAR("M19.43,12.97c0.04,-0.32 0.07,-0.64 0.07,-0.97c0,-0.33 -0.03,-0.66 -0.07,-0.98l2.11,-1.65"
                + "c0.19,-0.15 0.24,-0.42 0.12,-0.64l-2,-3.46c-0.12,-0.22 -0.39,-0.3 -0.61,-0.22l-2.49,1c-0.52,-0.4 -1.08,-0.73"
                + " -1.69,-0.98l-0.38,-2.65C14.46,2.18 14.25,2 14,2h-4c-0.25,0 -0.46,0.18 -0.49,0.42l-0.38,2.65c-0.61,0.25"
                + " -1.17,0.59 -1.69,0.98l-2.49,-1c-0.23,-0.09 -0.49,0 -0.61,0.22l-2,3.46c-0.13,0.22 -0.07,0.49 0.12,0.64l2.11,1.65"
                + "c-0.04,0.32 -0.07,0.65 -0.07,0.98c0,0.33 0.03,0.66 0.07,0.98l-2.11,1.65c-0.19,0.15 -0.24,0.42 -0.12,0.64l2,3.46"
                + "c0.12,0.22 0.39,0.3 0.61,0.22l2.49,-1c0.52,0.4 1.08,0.73 1.69,0.98l0.38,2.65c0.03,0.24 0.24,0.42 0.49,0.42h4"
                + "c0.25,0 0.46,-0.18 0.49,-0.42l0.38,-2.65c0.61,-0.25 1.17,-0.59 1.69,-0.98l2.49,1c0.23,0.09 0.49,0 0.61,-0.22l2,-3.46"
                + "c0.12,-0.22 0.07,-0.49 -0.12,-0.64l-2.11,-1.65M12,15.5A3.5,3.5 0 0,1 8.5,12A3.5,3.5 0 0,1 12,8.5"
                + "A3.5,3.5 0 0,1 15.5,12A3.5,3.5 0 0,1 12,15.5Z", "#555555"),
        /** 键盘：插入工业符号 */
        KEYBOARD("M20,5H4C2.9,5 2,5.9 2,7v10c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7C22,5.9 21.1,5 20,5M20,17H4V7h16V17M6,8h2v2H6V8M9,8h2v2H9V8M12,8h2v2h-2V8M15,8h2v2h-2V8M6,11h2v2H6V11M9,11h2v2H9V11M12,11h2v2h-2V11M15,11h3v2h-3V11M6,14h10v2H6V14Z",
                "#5f7f7f");

        private final String path;
        private final String color;

        Kind(String path, String color) {
            this.path = path;
            this.color = color;
        }
    }

    public static Node graphic(Kind kind, double size) {
        return graphic(kind, size, Color.web(kind.color));
    }

    public static Node graphic(Kind kind, double size, Color color) {
        SVGPath shape = new SVGPath();
        shape.setContent(kind.path);
        shape.setFill(color);
        double scale = size / VIEW;
        shape.setScaleX(scale);
        shape.setScaleY(scale);
        StackPane pane = new StackPane(shape);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        pane.setMouseTransparent(true);
        return pane;
    }

    public static Button iconButton(Kind kind, String tooltip, boolean toolbar) {
        Button button = new Button();
        button.setGraphic(graphic(kind, toolbar ? 16 : 18));
        button.setMinSize(toolbar ? 28 : 26, toolbar ? 28 : 26);
        button.setPrefSize(toolbar ? 28 : 26, toolbar ? 28 : 26);
        button.setMaxSize(toolbar ? 28 : 26, toolbar ? 28 : 26);
        button.getStyleClass().add(toolbar ? "icon-toolbar-btn" : "icon-action-btn");
        if (tooltip != null && !tooltip.isBlank()) {
            button.setTooltip(styledTooltip(tooltip));
        }
        return button;
    }

    /** 白底深字，避免默认黑底看不清。 */
    public static javafx.scene.control.Tooltip styledTooltip(String text) {
        javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(text);
        tip.getStyleClass().add("app-tooltip");
        return tip;
    }
}
