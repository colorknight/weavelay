package com.weavelay.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把进程临时目录拨到工程盘（默认仓库 {@code .tmp}），避免 C: 满导致 ORT 抽原生库失败。
 * <p>可用环境变量 {@code WEAVELAY_TMP} 覆盖。
 */
public final class TempDirBootstrap {

    private static final String PROP = "java.io.tmpdir";
    private static volatile boolean done;

    private TempDirBootstrap() {
    }

    public static void ensure() {
        if (done) {
            return;
        }
        synchronized (TempDirBootstrap.class) {
            if (done) {
                return;
            }
            Path dir = resolveTempDir();
            if (dir != null) {
                try {
                    Files.createDirectories(dir);
                    System.setProperty(PROP, dir.toAbsolutePath().normalize().toString());
                } catch (IOException ignored) {
                    // 保持系统默认 TEMP
                }
            }
            done = true;
        }
    }

    public static Path currentTmpDir() {
        return Paths.get(System.getProperty(PROP, System.getProperty("java.io.tmpdir", ".")));
    }

    private static Path resolveTempDir() {
        String override = System.getenv("WEAVELAY_TMP");
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        Path repoTmp = locateRepoRoot().resolve(".tmp");
        if (isWritableParent(repoTmp)) {
            return repoTmp;
        }
        // 工程若在 C: 再兜底到 E:\tmp\weavelay
        Path eFallback = Paths.get("E:/tmp/weavelay");
        if (Files.isDirectory(eFallback.getRoot()) && isWritableParent(eFallback)) {
            return eFallback;
        }
        return null;
    }

    private static Path locateRepoRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path[] candidates = {
                cwd,
                cwd.resolve("..").normalize(),
                cwd.resolve("../..").normalize()
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("pom.xml"))
                    && Files.isDirectory(c.resolve("weavelay-ocr"))) {
                return c;
            }
        }
        return cwd;
    }

    private static boolean isWritableParent(Path dir) {
        try {
            Path parent = dir.getParent() == null ? dir : dir.getParent();
            if (!Files.isDirectory(parent)) {
                return false;
            }
            Path probe = parent.resolve(".weavelay-tmp-probe");
            Files.createDirectories(parent);
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
