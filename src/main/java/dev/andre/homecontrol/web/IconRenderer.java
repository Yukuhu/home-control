package dev.andre.homecontrol.web;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Draws the app icon with Java2D so no binary artwork lives in the repository. */
public final class IconRenderer {

    static final Color BACKGROUND = new Color(0x101917);
    static final Color FOREGROUND = new Color(0xeef3ec);
    static final Color ACCENT = new Color(0xb7e6a0);

    public enum Shape { ROUNDED, FULL_BLEED, MASKABLE }

    private IconRenderer() {
    }

    public static byte[] png(int size, Shape shape) {
        BufferedImage image = render(size, shape);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage render(int size, Shape shape) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(BACKGROUND);
            if (shape == Shape.ROUNDED) {
                g.fill(new RoundRectangle2D.Double(0, 0, size, size, 0.44 * size, 0.44 * size));
            } else {
                g.fill(new Rectangle2D.Double(0, 0, size, size));
            }
            double scale = shape == Shape.MASKABLE ? 0.8 : 1.0;
            double offset = (1 - scale) / 2 * size;
            double u = scale * size;
            g.translate(offset, offset);
            g.setColor(FOREGROUND);
            g.setStroke(new BasicStroke((float) (0.045 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Double(0.18 * u, 0.24 * u, 0.64 * u, 0.44 * u, 0.10 * u, 0.10 * u));
            g.fill(new Rectangle2D.Double(0.40 * u, 0.76 * u, 0.20 * u, 0.04 * u));
            g.setColor(ACCENT);
            Path2D play = new Path2D.Double();
            play.moveTo(0.44 * u, 0.35 * u);
            play.lineTo(0.62 * u, 0.46 * u);
            play.lineTo(0.44 * u, 0.57 * u);
            play.closePath();
            g.fill(play);
        } finally {
            g.dispose();
        }
        return image;
    }
}
