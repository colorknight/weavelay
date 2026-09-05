package com.weavelay.core.license;

import com.weavelay.core.WeavelayDataDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Licence 文件读写：默认 {@code WEAVELAY_HOME/license.weavelaylic}。
 */
public final class LicenseStore {

    public static final String FILE_NAME = "license" + LicenseDocument.FILE_EXTENSION;

    private final Path licensePath;

    public LicenseStore() {
        this(WeavelayDataDir.get().resolve(FILE_NAME));
    }

    public LicenseStore(Path licensePath) {
        this.licensePath = licensePath;
    }

    public Path getLicensePath() {
        return licensePath;
    }

    public boolean exists() {
        return Files.isRegularFile(licensePath);
    }

    public LicenseDocument load() throws IOException {
        if (!exists()) {
            return null;
        }
        String json = Files.readString(licensePath, StandardCharsets.UTF_8);
        return LicenseDocument.parse(json);
    }

    public void save(LicenseDocument doc) throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("doc required");
        }
        Files.createDirectories(licensePath.getParent());
        Path tmp = licensePath.resolveSibling(licensePath.getFileName() + ".tmp");
        Files.writeString(tmp, doc.toPrettyJson(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, licensePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(tmp, licensePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void importFrom(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("licence 文件不存在: " + source);
        }
        LicenseDocument doc = LicenseDocument.parse(Files.readString(source, StandardCharsets.UTF_8));
        save(doc);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(licensePath);
    }
}
