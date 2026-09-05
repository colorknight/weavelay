package com.weavelay.app;

import com.weavelay.ocr.OcrParamSettings;
import com.weavelay.ocr.OcrRuntimeConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 记住上次浏览目录、已选输入路径与 OCR 参数.
 */
public final class AppPreferences {

    enum InputKind {
        PDF_FILE,
        PDF_FOLDER;

        static InputKind parse(String value) {
            if (value == null) {
                return null;
            }
            try {
                return InputKind.valueOf(value.trim());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private static final Path CONFIG_PATH =
            com.weavelay.core.WeavelayDataDir.get().resolve("preferences.properties");

    private final Properties props = new Properties();

    void load() {
        props.clear();
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException ignored) {
            props.clear();
        }
    }

    void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                props.store(writer, "WeaveLay preferences");
            }
        } catch (IOException ignored) {
            // 偏好写入失败不影响主流程
        }
    }

    public OcrParamSettings getOcrParams() {
        OcrParamSettings defaults = OcrParamSettings.defaults();
        return new OcrParamSettings(
                parseIntProp("ocr.padding", defaults.getPadding()),
                parseIntProp("ocr.maxSideLen", defaults.getMaxSideLen()),
                parseFloatProp("ocr.boxScoreThresh", defaults.getBoxScoreThresh()),
                parseFloatProp("ocr.boxThresh", defaults.getBoxThresh()),
                parseFloatProp("ocr.unClipRatio", defaults.getUnClipRatio()),
                parseFloatProp("ocr.minBoxScore", defaults.getMinBoxScore()));
    }

    public void saveOcrParams(OcrParamSettings params) {
        if (params == null) {
            return;
        }
        props.setProperty("ocr.padding", String.valueOf(params.getPadding()));
        props.setProperty("ocr.maxSideLen", String.valueOf(params.getMaxSideLen()));
        props.setProperty("ocr.boxScoreThresh", String.valueOf(params.getBoxScoreThresh()));
        props.setProperty("ocr.boxThresh", String.valueOf(params.getBoxThresh()));
        props.setProperty("ocr.unClipRatio", String.valueOf(params.getUnClipRatio()));
        props.setProperty("ocr.minBoxScore", String.valueOf(params.getMinBoxScore()));
        props.remove("ocr.preprocess");
        save();
    }

    public OcrRuntimeConfig getOcrRuntime() {
        return new OcrRuntimeConfig();
    }

    public void saveOcrRuntime(OcrRuntimeConfig config) {
        // 模型目录由程序内部解析，不写入用户偏好
        props.remove("ocr.v6ModelDir");
        save();
    }

    public void saveOcrSettings(OcrParamSettings params, OcrRuntimeConfig runtime) {
        if (params != null) {
            props.setProperty("ocr.padding", String.valueOf(params.getPadding()));
            props.setProperty("ocr.maxSideLen", String.valueOf(params.getMaxSideLen()));
            props.setProperty("ocr.boxScoreThresh", String.valueOf(params.getBoxScoreThresh()));
            props.setProperty("ocr.boxThresh", String.valueOf(params.getBoxThresh()));
            props.setProperty("ocr.unClipRatio", String.valueOf(params.getUnClipRatio()));
            props.setProperty("ocr.minBoxScore", String.valueOf(params.getMinBoxScore()));
            props.remove("ocr.preprocess");
        }
        props.remove("ocr.v6ModelDir");
        save();
    }

    Path getLastBrowseDirectory() {
        return toExistingPath(props.getProperty("last.browse.dir"));
    }

    Path getLastInputPath() {
        return toExistingPath(props.getProperty("last.input.path"));
    }

    InputKind getLastInputKind() {
        return InputKind.parse(props.getProperty("last.input.kind"));
    }

    void rememberBrowseDirectory(Path path) {
        Path dir = resolveBrowseDirectory(path);
        if (dir == null) {
            return;
        }
        props.setProperty("last.browse.dir", dir.toAbsolutePath().toString());
        save();
    }

    void rememberInput(Path path, InputKind kind) {
        if (path == null || kind == null) {
            return;
        }
        props.setProperty("last.input.path", path.toAbsolutePath().toString());
        props.setProperty("last.input.kind", kind.name());
        rememberBrowseDirectory(path);
    }

    /**
     * 记住当前处理到的 PDF（断点）。不写处理历史，只保留一份路径。
     */
    void rememberWorkFile(Path pdf) {
        if (pdf == null) {
            return;
        }
        props.setProperty("last.work.file", pdf.toAbsolutePath().normalize().toString());
        Path parent = pdf.getParent();
        if (parent != null) {
            rememberBrowseDirectory(parent);
        } else {
            save();
        }
    }

    /** 上次处理到的 PDF；文件不存在则返回 null。 */
    Path getLastWorkFile() {
        return toExistingPath(props.getProperty("last.work.file"));
    }

    public int getExportParallelism() {
        return parseIntProp("export.parallelism", 2);
    }

    public void saveExportParallelism(int parallelism) {
        props.setProperty("export.parallelism", String.valueOf(Math.max(1, parallelism)));
        save();
    }

    public Path getExportDir() {
        return toExistingDir(props.getProperty("export.dir"));
    }

    public void setExportDir(Path dir) {
        if (dir != null && Files.isDirectory(dir)) {
            props.setProperty("export.dir", dir.toAbsolutePath().toString());
        } else {
            props.remove("export.dir");
        }
        save();
    }

    /** 主页工作模式：模板（圈框定规则） / 应用（确认写出）。 */
    public enum WorkMode {
        TEMPLATE,
        APPLY;

        static WorkMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return TEMPLATE;
            }
            try {
                return WorkMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return TEMPLATE;
            }
        }
    }

    public WorkMode getWorkMode() {
        return WorkMode.parse(props.getProperty("ui.workMode", "TEMPLATE"));
    }

    public void setWorkMode(WorkMode mode) {
        props.setProperty("ui.workMode", mode == null ? "TEMPLATE" : mode.name());
        save();
    }

    /** 预览缩放：0 表示适应宽度；缺省 0.3（30%）。 */
    public double getPreviewZoom() {
        String value = props.getProperty("ui.previewZoom");
        if (value == null || value.trim().isEmpty()) {
            return 0.3;
        }
        String trimmed = value.trim();
        if ("fit".equalsIgnoreCase(trimmed)) {
            return 0;
        }
        try {
            double zoom = Double.parseDouble(trimmed);
            if (zoom <= 0) {
                return 0;
            }
            return Math.max(0.05, Math.min(8.0, zoom));
        } catch (NumberFormatException ex) {
            return 0.3;
        }
    }

    public void setPreviewZoom(double zoom) {
        if (zoom <= 0) {
            props.setProperty("ui.previewZoom", "fit");
        } else {
            props.setProperty("ui.previewZoom", String.valueOf(Math.max(0.05, Math.min(8.0, zoom))));
        }
        save();
    }

    private static Path toExistingDir(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            Path path = Paths.get(value.trim());
            return Files.isDirectory(path) ? path : null;
        } catch (Exception ex) { return null; }
    }

    Path resolveInitialBrowseDirectory() {
        Path browse = getLastBrowseDirectory();
        if (browse != null) {
            return browse;
        }
        Path input = getLastInputPath();
        if (input != null) {
            return Files.isDirectory(input) ? input : input.getParent();
        }
        return null;
    }

    private int parseIntProp(String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private float parseFloatProp(String key, float defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean parseBoolProp(String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static Path resolveBrowseDirectory(Path path) {
        if (path == null) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) {
            return absolute;
        }
        Path parent = absolute.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            return parent;
        }
        return null;
    }

    private static Path toExistingPath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            Path path = Paths.get(value.trim());
            return Files.exists(path) ? path : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
