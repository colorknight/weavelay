package com.weavelay.app.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 页图同步加载, 避免 D3D 纹理在异步未完成时被绘制.
 */
final class PageImageFactory {

    private PageImageFactory() {
    }

    static Image loadSync(byte[] pagePng) {
        return toWritableImage(loadBuffered(pagePng));
    }

    static BufferedImage loadBuffered(byte[] pagePng) {
        if (pagePng == null || pagePng.length == 0) {
            throw new IllegalArgumentException("empty page png");
        }
        try {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(pagePng));
            if (buffered == null) {
                throw new IllegalStateException("ImageIO returned null");
            }
            int width = buffered.getWidth();
            int height = buffered.getHeight();
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("page image has invalid size");
            }
            return buffered;
        } catch (IOException ex) {
            throw new IllegalStateException("page image load failed", ex);
        }
    }

    static WritableImage toWritableImage(BufferedImage buffered) {
        int width = buffered.getWidth();
        int height = buffered.getHeight();
        WritableImage image = new WritableImage(width, height);
        PixelWriter writer = image.getPixelWriter();
        int[] pixels = new int[width * height];
        buffered.getRGB(0, 0, width, height, pixels, 0, width);
        writer.setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }
}
