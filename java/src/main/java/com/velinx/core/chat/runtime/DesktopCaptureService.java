package com.velinx.core.chat.runtime;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class DesktopCaptureService {

    private static final int MAX_DIMENSION = 1920;
    private static final String MIME_TYPE = "image/png";

    public DesktopScreenshot captureDesktop() {
        try {
            Rectangle captureBounds = resolveCaptureBounds();
            BufferedImage screenshot = new Robot().createScreenCapture(captureBounds);
            BufferedImage normalized = scaleIfNeeded(screenshot);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(normalized, "png", outputStream);

            return new DesktopScreenshot(
                    MIME_TYPE,
                    Base64.getEncoder().encodeToString(outputStream.toByteArray()),
                    normalized.getWidth(),
                    normalized.getHeight()
            );
        } catch (AWTException | IOException e) {
            throw new IllegalStateException("Failed to capture desktop screenshot", e);
        }
    }

    private Rectangle resolveCaptureBounds() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = environment.getScreenDevices();
        if (devices == null || devices.length == 0) {
            var screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(screenSize);
        }

        Rectangle bounds = null;
        for (GraphicsDevice device : devices) {
            Rectangle screenBounds = device.getDefaultConfiguration().getBounds();
            bounds = (bounds == null) ? new Rectangle(screenBounds) : bounds.union(screenBounds);
        }
        return bounds;
    }

    private BufferedImage scaleIfNeeded(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longestEdge = Math.max(width, height);
        if (longestEdge <= MAX_DIMENSION) {
            return source;
        }

        double scale = (double) MAX_DIMENSION / longestEdge;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }
}
