package com.weavelay.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 本地数据根目录（库、偏好、缓存）：默认 {@code E:/tmp/weavelay/home}，
 * 可用 {@code WEAVELAY_HOME} / {@code WEAVELAY_TMP} 覆盖，避免写入 C: 用户目录。
 */
public final class WeavelayDataDir {

    private static final Path E_DEFAULT = Paths.get("E:/tmp/weavelay/home");
    private static volatile Path cached;

    private WeavelayDataDir() {}

    public static Path get() {
        Path hit = cached;
        if (hit != null) {
            return hit;
        }
        synchronized (WeavelayDataDir.class) {
            if (cached != null) {
                return cached;
            }
            Path dir = resolve();
            try {
                Files.createDirectories(dir);
                migrateFromLegacyIfNeeded(dir);
            } catch (IOException ignored) {
            }
            cached = dir;
            return dir;
        }
    }

    private static Path resolve() {
        String home = System.getenv("WEAVELAY_HOME");
        if (home != null && !home.isBlank()) {
            return Paths.get(home.trim());
        }
        String tmp = System.getenv("WEAVELAY_TMP");
        if (tmp != null && !tmp.isBlank()) {
            return Paths.get(tmp.trim()).resolve("home");
        }
        if (isUsable(E_DEFAULT)) {
            return E_DEFAULT;
        }
        // 最后才回落到用户目录（不推荐）
        return Paths.get(System.getProperty("user.home", "."), ".weavelay");
    }

    private static boolean isUsable(Path dir) {
        try {
            Path root = dir.getRoot();
            if (root == null || !Files.isDirectory(root)) {
                return false;
            }
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 若旧 C: {@code ~/.weavelay} 有库/偏好而新目录没有，复制过去一次。 */
    private static void migrateFromLegacyIfNeeded(Path target) {
        Path legacy = Paths.get(System.getProperty("user.home", ""), ".weavelay");
        if (!Files.isDirectory(legacy) || legacy.toAbsolutePath().normalize()
                .equals(target.toAbsolutePath().normalize())) {
            return;
        }
        try {
            Path newDb = target.resolve("weavelay.db");
            Path oldDb = legacy.resolve("weavelay.db");
            if (!Files.isRegularFile(newDb) && Files.isRegularFile(oldDb)) {
                Files.copy(oldDb, newDb, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path newPrefs = target.resolve("preferences.properties");
            Path oldPrefs = legacy.resolve("preferences.properties");
            if (!Files.isRegularFile(newPrefs) && Files.isRegularFile(oldPrefs)) {
                Files.copy(oldPrefs, newPrefs, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path newCache = target.resolve("cache");
            Path oldCache = legacy.resolve("cache");
            if (Files.isDirectory(oldCache) && !Files.isDirectory(newCache)) {
                copyTree(oldCache, newCache);
            }
        } catch (IOException ignored) {
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path rel = src.relativize(p);
                Path out = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out, StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
