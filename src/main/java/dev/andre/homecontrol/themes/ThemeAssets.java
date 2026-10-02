package dev.andre.homecontrol.themes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

final class ThemeAssets {
    private static final int VP8 = 0x20385056;
    private static final int VP8L = 0x4c385056;
    private static final int VP8X = 0x58385056;

    private ThemeAssets() { }

    static Map<String, String> validate(Map<String, byte[]> files) {
        Map<String, String> contentTypes = new LinkedHashMap<>();
        files.forEach((path, bytes) -> {
            if (path.equals("LICENSE") || path.endsWith(".txt")) {
                String text = ThemeArchive.utf8(bytes);
                if (text.indexOf('\0') >= 0 || text.length() > ThemeArchive.MAX_JSON) throw ThemeException.invalid("License text is invalid or too large.");
            } else if (path.equals("preview.png") || path.startsWith("assets/")) {
                String type;
                if (path.endsWith(".woff2")) { font(bytes); type = "font/woff2"; }
                else if (path.endsWith(".webp")) { webp(bytes); type = "image/webp"; }
                else { type = image(bytes, path); }
                contentTypes.put(path, type);
            }
        });
        return Map.copyOf(contentTypes);
    }

    private static void font(byte[] bytes) {
        if (bytes.length < 48) throw ThemeException.invalid("Invalid WOFF2 font.");
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int flavor = data.getInt(4);
        int tables = Short.toUnsignedInt(data.getShort(12));
        if (data.getInt() != 0x774f4632 || Integer.toUnsignedLong(data.getInt(8)) != bytes.length
                || (flavor != 0x00010000 && flavor != 0x4f54544f) || tables < 1 || tables > 256 || data.getShort(14) != 0
                || Integer.toUnsignedLong(data.getInt(16)) > 60L * 1024 * 1024
                || Integer.toUnsignedLong(data.getInt(20)) > bytes.length - 48) throw ThemeException.invalid("Invalid WOFF2 font header.");
    }

    private static String image(byte[] bytes, String path) {
        String expected = path.endsWith(".png") ? "png" : "JPEG";
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw ThemeException.invalid("Theme image content does not match its extension.");
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(expected)) throw ThemeException.invalid("Theme image content does not match its extension.");
                reader.setInput(stream, true, true);
                dimensions(reader.getWidth(0), reader.getHeight(0));
                if (reader.read(0) == null) throw ThemeException.invalid("Theme image cannot be decoded.");
            } finally { reader.dispose(); }
            return expected.equals("png") ? "image/png" : "image/jpeg";
        } catch (IOException | IllegalArgumentException _) { throw ThemeException.invalid("Theme image cannot be decoded."); }
    }

    private static void webp(byte[] bytes) {
        if (bytes.length < 30) throw ThemeException.invalid("Invalid WebP image.");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || Integer.toUnsignedLong(b.getInt(4)) + 8 != bytes.length || b.getInt(8) != 0x50424557) throw ThemeException.invalid("Invalid WebP container.");
        ImageDimensions canvas = null;
        boolean image = false;
        int position = 12;
        while (position < bytes.length) {
            WebpChunk chunk = webpChunk(bytes, b, position);
            if (chunk.dimensions() != null && (canvas == null || !chunk.frame())) canvas = chunk.dimensions();
            image |= chunk.frame();
            position = chunk.next();
        }
        if (!image || canvas == null) throw ThemeException.invalid("WebP image has no frame.");
        dimensions(canvas.width(), canvas.height());
    }

    private static WebpChunk webpChunk(byte[] bytes, ByteBuffer data, int position) {
        if (position + 8 > bytes.length) throw ThemeException.invalid("Invalid WebP chunk.");
        int kind = data.getInt(position);
        long size = Integer.toUnsignedLong(data.getInt(position + 4));
        if (size > bytes.length - position - 8) throw ThemeException.invalid("Invalid WebP chunk length.");
        ImageDimensions sizeInPixels = webpDimensions(bytes, data, kind, position + 8, size);
        int next = position + 8 + (int) size + ((int) size & 1);
        if (next > bytes.length) throw ThemeException.invalid("Invalid WebP padding.");
        return new WebpChunk(next, sizeInPixels, kind == VP8 || kind == VP8L);
    }

    private static ImageDimensions webpDimensions(byte[] bytes, ByteBuffer data, int kind, int start, long size) {
        if (kind == VP8X && size == 10) return webpCanvas(bytes, start);
        if (kind == VP8 && size >= 10) return webpLossyFrame(bytes, data, start);
        if (kind == VP8L && size >= 5) return webpLosslessFrame(bytes, data, start);
        if (kind != 0x48504c41 && kind != 0x50434349 && kind != 0x46495845 && kind != 0x20504d58) {
            throw ThemeException.invalid("Unsupported WebP chunk.");
        }
        return null;
    }

    private static ImageDimensions webpCanvas(byte[] bytes, int start) {
        // VP8X canvas: animated WebP is deliberately outside version 1.
        if ((bytes[start] & 2) != 0) throw ThemeException.invalid("Animated WebP is not supported.");
        return new ImageDimensions(1 + little24(bytes, start + 4), 1 + little24(bytes, start + 7));
    }

    private static ImageDimensions webpLossyFrame(byte[] bytes, ByteBuffer data, int start) {
        if ((bytes[start] & 1) != 0 || little24(bytes, start + 3) != 0x2a019d) throw ThemeException.invalid("Invalid WebP frame.");
        int width = Short.toUnsignedInt(data.getShort(start + 6)) & 0x3fff;
        int height = Short.toUnsignedInt(data.getShort(start + 8)) & 0x3fff;
        dimensions(width, height);
        return new ImageDimensions(width, height);
    }

    private static ImageDimensions webpLosslessFrame(byte[] bytes, ByteBuffer data, int start) {
        if (bytes[start] != 0x2f) throw ThemeException.invalid("Invalid WebP lossless frame.");
        int bits = data.getInt(start + 1);
        if ((bits >>> 29) != 0) throw ThemeException.invalid("Unsupported WebP lossless version.");
        int width = 1 + (bits & 0x3fff);
        int height = 1 + ((bits >>> 14) & 0x3fff);
        dimensions(width, height);
        return new ImageDimensions(width, height);
    }

    private record ImageDimensions(int width, int height) { }
    private record WebpChunk(int next, ImageDimensions dimensions, boolean frame) { }

    private static int little24(byte[] bytes, int p) { return Byte.toUnsignedInt(bytes[p]) | Byte.toUnsignedInt(bytes[p + 1]) << 8 | Byte.toUnsignedInt(bytes[p + 2]) << 16; }
    private static void dimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096) throw ThemeException.invalid("Theme images must be between 1 and 4096 pixels in each dimension.");
    }
}
