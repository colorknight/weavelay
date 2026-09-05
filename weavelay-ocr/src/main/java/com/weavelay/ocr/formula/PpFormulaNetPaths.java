package com.weavelay.ocr.formula;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PP-FormulaNet ONNX 模型路径解析。
 *
 * <p>支持两种布局：
 * <ul>
 *   <li>导出工程根：{@code output/onnx_models/{backbone,head_fixed}.onnx}
 *       + {@code ppocr/utils/dict/unimernet_tokenizer/tokenizer.json}</li>
 *   <li>扁平目录：同目录下 {@code backbone.onnx} / {@code head_fixed.onnx} / {@code tokenizer.json}</li>
 * </ul>
 *
 * <p>查找顺序：系统属性 {@code weavelay.formula.root} → 环境变量 {@code WEAVELAY_FORMULA_ROOT}
 * → {@code models/pp-formula}（便携包）→ {@code weavelay-ocr/models/pp-formula}
 * → {@code E:/IdeaProjects/pp_formula_to_onnx}（开发机回退）
 */
public final class PpFormulaNetPaths {

    public record ModelFiles(Path backbone, Path head, Path tokenizer) {}

    private PpFormulaNetPaths() {}

    public static ModelFiles resolve() {
        for (Path root : candidateRoots()) {
            ModelFiles files = fromRoot(root);
            if (files != null) {
                return files;
            }
        }
        return null;
    }

    public static boolean isReady() {
        return resolve() != null;
    }

    private static Path[] candidateRoots() {
        String prop = System.getProperty("weavelay.formula.root");
        String env = System.getenv("WEAVELAY_FORMULA_ROOT");
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return new Path[] {
                prop != null && !prop.isBlank() ? Paths.get(prop.trim()) : null,
                env != null && !env.isBlank() ? Paths.get(env.trim()) : null,
                cwd.resolve("weavelay-ocr/models/pp-formula"),
                cwd.resolve("models/pp-formula"),
                cwd.resolve("../weavelay-ocr/models/pp-formula").normalize(),
                Paths.get("E:/IdeaProjects/pp_formula_to_onnx")
        };
    }

    static ModelFiles fromRoot(Path root) {
        if (root == null) {
            return null;
        }
        Path flatBackbone = root.resolve("backbone.onnx");
        Path flatHead = root.resolve("head_fixed.onnx");
        Path flatTok = root.resolve("tokenizer.json");
        if (isFile(flatBackbone) && isFile(flatHead) && isFile(flatTok)) {
            return new ModelFiles(flatBackbone, flatHead, flatTok);
        }
        Path backbone = root.resolve("output/onnx_models/backbone.onnx");
        Path head = root.resolve("output/onnx_models/head_fixed.onnx");
        Path tok = root.resolve("ppocr/utils/dict/unimernet_tokenizer/tokenizer.json");
        if (isFile(backbone) && isFile(head) && isFile(tok)) {
            return new ModelFiles(backbone, head, tok);
        }
        return null;
    }

    private static boolean isFile(Path p) {
        return p != null && Files.isRegularFile(p);
    }
}
