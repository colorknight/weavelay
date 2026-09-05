package com.weavelay.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class PpOcrV5ModelInstaller {

    static final String DET_FILE = "ppocrv6_medium_det.onnx";
    static final String REC_FILE = "ppocrv6_medium_rec.onnx";
    static final String KEYS_FILE = "ppocrv6_dict.txt";
    static final String CLS_FILE = "ch_ppocr_mobile_v2.0_cls_infer.onnx";

    private PpOcrV5ModelInstaller() {
    }

    static void prepareModelDir(Path modelDir) throws IOException {
        if (modelDir == null || !Files.isDirectory(modelDir)) {
            throw new IllegalStateException("v5 模型目录不存在: " + modelDir);
        }
        requireFile(modelDir.resolve(DET_FILE), DET_FILE);
        requireFile(modelDir.resolve(REC_FILE), REC_FILE);
        requireFile(modelDir.resolve(KEYS_FILE), KEYS_FILE);
        Path cls = modelDir.resolve(CLS_FILE);
        if (!Files.isRegularFile(cls)) {
            copyBundledCls(cls);
        }
    }

    private static void requireFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("缺少 v5 模型文件 " + label + ": " + path);
        }
    }

    private static void copyBundledCls(Path target) throws IOException {
        String[] resources = {
                "models/" + CLS_FILE,
                "models/ch_ppocr_mobile_v2.0_cls_infer.onnx"
        };
        ClassLoader loader = PpOcrV5ModelInstaller.class.getClassLoader();
        for (String resource : resources) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in != null) {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IllegalStateException(
                "无法从 RapidOCR4j 资源包复制方向模型, 请将 " + CLS_FILE + " 放入模型目录");
    }
}
