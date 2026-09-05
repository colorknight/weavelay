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
 * 只读 JSON 树形查看：可折叠，适合「当前输出 JSON」这类很长的数据集。
 */
public final class JsonViewerWebPane extends StackPane {

    private final WebView webView = new WebView();
    private final WebEngine engine = webView.getEngine();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final List<Runnable> pending = new ArrayList<>();

    public JsonViewerWebPane() {
        getChildren().add(webView);
        webView.setContextMenuEnabled(false);
        engine.setJavaScriptEnabled(true);
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    window.setMember("javaBridge", new JavaBridge());
                    engine.executeScript(
                            "if (typeof notifyJavaReady === 'function') notifyJavaReady();");
                } catch (Exception ex) {
                    System.err.println("[json-viewer] bridge inject failed: " + ex.getMessage());
                }
            } else if (state == Worker.State.FAILED) {
                System.err.println("[json-viewer] load failed: " + engine.getLoadWorker().getException());
            }
        });
        var url = JsonViewerWebPane.class.getResource("/web/json-viewer/viewer.html");
        if (url == null) {
            throw new IllegalStateException("missing /web/json-viewer/viewer.html");
        }
        engine.load(url.toExternalForm());
    }

    public void setText(String text) {
        runWhenReady(() -> callWindow("viewerSetText", text == null ? "" : text));
    }

    public String getPrettyText() {
        if (!ready.get()) {
            return "";
        }
        try {
            Object value = engine.executeScript("viewerGetText()");
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ex) {
            return "";
        }
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
                System.err.println("[json-viewer] pending action failed: " + ex.getMessage());
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
                    flushPending();
                }
            });
        }
    }
}
