package com.weavelay.ocr;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PpOcrV5WarmupImage {

    private static volatile Path cached;

    private PpOcrV5WarmupImage() {
    }

    static Path ensure() throws IOException {
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (PpOcrV5WarmupImage.class) {
            if (cached != null && Files.isRegularFile(cached)) {
                return cached;
            }
            Path dir = com.weavelay.core.WeavelayDataDir.get().resolve("cache");
            Files.createDirectories(dir);
            Path png = dir.resolve("v5-warmup.png");
            if (!Files.isRegularFile(png)) {
                BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 64, 32);
                g.setColor(Color.BLACK);
                g.drawString("WL", 8, 22);
                g.dispose();
                ImageIO.write(image, "png", png.toFile());
            }
            cached = png;
            return png;
        }
    }
}
