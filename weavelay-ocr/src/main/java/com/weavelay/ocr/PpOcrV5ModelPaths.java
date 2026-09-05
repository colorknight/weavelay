package com.weavelay.ocr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PP-OCRv5 模型目录: 工程内 {@code weavelay-ocr/models/ppocrv5} (纯 ASCII 路径, 供 native exe 加载).
 */
public final class PpOcrV5ModelPaths {

    private static final String MODEL_SUBDIR = "weavelay-ocr/models/ppocrv6";

    private PpOcrV5ModelPaths() {
    }

    public static Path defaultModelDir() {
        Path resolved = resolveExisting();
        return resolved == null ? fallbackModelDir() : resolved;
    }

    public static boolean isReady(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return Files.isRegularFile(dir.resolve(PpOcrV5ModelInstaller.DET_FILE))
                && Files.isRegularFile(dir.resolve(PpOcrV5ModelInstaller.REC_FILE))
                && Files.isRegularFile(dir.resolve(PpOcrV5ModelInstaller.KEYS_FILE));
    }

    public static boolean isAsciiPath(Path path) {
        if (path == null) {
            return false;
        }
        String text = path.toAbsolutePath().toString();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static Path resolveExisting() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path[] candidates = {
                cwd.resolve(MODEL_SUBDIR),
                cwd.resolve("models/ppocrv6"),
                cwd.resolve("../weavelay-ocr/models/ppocrv6").normalize(),
                cwd.resolve("../../weavelay-ocr/models/ppocrv6").normalize()
        };
        for (Path candidate : candidates) {
            if (isReady(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path fallbackModelDir() {
        return Paths.get(System.getProperty("user.dir"))
                .resolve(MODEL_SUBDIR)
                .toAbsolutePath()
                .normalize();
    }
}
