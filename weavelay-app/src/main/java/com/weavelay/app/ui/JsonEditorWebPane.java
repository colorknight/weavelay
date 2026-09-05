package com.weavelay.app.ui;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 JSON 编辑器（WebView，不依赖外网 CDN）：校验、格式化。
 */
final class JsonEditorWebPane extends StackPane {

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final List<Runnable> pending = new ArrayList<>();
    private Runnable onBlur;

    JsonEditorWebPane() {
        getChildren().add(webView);
        webView.setContextMenuEnabled(false);
        engine.setJavaScriptEnabled(true);
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    window.setMember("javaBridge", new JavaBridge());
                    // 页面可能已先 ready，补一次握手
                    engine.executeScript(
                            "if (typeof notifyJavaReady === 'function') notifyJavaReady();");
                } catch (Exception ex) {
                    System.err.println("[json-editor] bridge inject failed: " + ex.getMessage());
                }
            } else if (state == Worker.State.FAILED) {
                System.err.println("[json-editor] load failed: " + engine.getLoadWorker().getException());
            }
        });
        var url = JsonEditorWebPane.class.getResource("/web/json-editor/editor.html");
        if (url == null) {
            throw new IllegalStateException("missing /web/json-editor/editor.html");
        }
        engine.load(url.toExternalForm());
    }

    void setOnBlur(Runnable handler) {
        this.onBlur = handler;
    }

    void setSchema(String schemaJson) {
        runWhenReady(() -> callWindow("editorSetSchema", schemaJson == null ? "" : schemaJson));
    }

    void setText(String text) {
        runWhenReady(() -> callWindow("editorSetValue", text == null ? "" : text));
    }

    String getText() {
        if (!ready.get()) {
            return "";
        }
        try {
            Object value = engine.executeScript("editorGetValue()");
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ex) {
            return "";
        }
    }

    void formatDocument() {
        runWhenReady(() -> {
            try {
                engine.executeScript("editorFormat()");
            } catch (Exception ignored) {
            }
        });
    }

    boolean isReady() {
        return ready.get();
    }

    private void runWhenReady(Runnable action) {
        if (ready.get()) {
            Platform.runLater(action);
            return;
        }
        synchronized (pending) {
            pending.add(action);
        }
    }

    private void flushPending() {
        List<Runnable> copy;
        synchronized (pending) {
            copy = new ArrayList<>(pending);
            pending.clear();
        }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (Exception ex) {
                System.err.println("[json-editor] pending action failed: " + ex.getMessage());
            }
        }
    }

    private void callWindow(String fn, Object... args) {
        JSObject window = (JSObject) engine.executeScript("window");
        window.call(fn, args);
    }

    @SuppressWarnings("unused")
    public final class JavaBridge {
        public void onReady() {
            Platform.runLater(() -> {
                if (ready.compareAndSet(false, true)) {
                    flushPending();
                } else {
                    // 已 ready 时仍冲一次，避免首屏 setText 丢了
                    flushPending();
                }
            });
        }

        public void onBlur() {
            Platform.runLater(() -> {
                if (onBlur != null) {
                    onBlur.run();
                }
            });
        }
    }
}
