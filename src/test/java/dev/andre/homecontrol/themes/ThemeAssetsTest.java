package dev.andre.homecontrol.themes;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeAssetsTest {
    private static final String FONT = "assets/font.woff2";
    private static final String WEBP = "assets/image.webp";

    @ParameterizedTest
    @ValueSource(strings = {
            "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoBAAEAAUAmJaACdLoB+AADsAD+8ut//NgVzXPv9//S4P0uD9Lg/9KQAAA=",
            "UklGRhwAAABXRUJQVlA4TA8AAAAvAAAAAAcQ/Y/+ByKi/wEA"})
    void acceptsEncodedLossyAndLosslessWebpImages(String encoded) {
        // Complete 1 x 1 red images encoded with libwebp, rather than header-only boundary fixtures.
        byte[] bytes = Base64.getDecoder().decode(encoded);
        assertThat(ThemeAssets.validate(Map.of(WEBP, bytes))).containsEntry(WEBP, "image/webp");
    }

    @ParameterizedTest
    @CsvSource({"png,preview.png,image/png", "png,assets/picture.png,image/png",
            "jpeg,assets/picture.jpg,image/jpeg", "jpeg,assets/picture.jpeg,image/jpeg"})
    void recognizesDecodedImageTypes(String format, String path, String contentType) throws IOException {
        byte[] bytes = image(format, 1, 4096);
        assertThat(ThemeAssets.validate(Map.of(path, bytes))).containsOnlyKeys(path).containsEntry(path, contentType);
    }

    @ParameterizedTest
    @CsvSource({"4097,1", "1,4097"})
    void rejectsEitherOversizedImageDimension(int width, int height) throws IOException {
        byte[] bytes = image("png", width, height);
        Map<String, byte[]> files = Map.of("preview.png", bytes);
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class).hasMessageContaining("4096");
    }

    @Test void rejectsImageWhoseContentDisagreesWithItsExtension() throws IOException {
        Map<String, byte[]> files = Map.of("assets/picture.jpg", image("png", 1, 1));
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class).hasMessageContaining("extension");
    }

    @Test void rejectsImageThatHasAHeaderButCannotBeDecoded() throws IOException {
        byte[] truncated = Arrays.copyOf(image("png", 1, 1), 40);
        Map<String, byte[]> files = Map.of("preview.png", truncated);
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class).hasMessageContaining("decoded");
    }

    @Test void validatesLicenseTextWithoutPublishingItAsAnAsset() {
        byte[] license = "é".repeat(128 * 1024).getBytes(StandardCharsets.UTF_8);
        assertThat(ThemeAssets.validate(Map.of("LICENSE", license, "assets/license.txt", license))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nul", "too-long", "invalid-utf8"})
    void rejectsInvalidLicenseText(String invalid) {
        byte[] bytes = switch (invalid) {
            case "nul" -> new byte[] {'a', 0};
            case "too-long" -> new byte[128 * 1024 + 1];
            default -> new byte[] {(byte) 0xc3, 0x28};
        };
        if (invalid.equals("too-long")) Arrays.fill(bytes, (byte) 'a');
        Map<String, byte[]> files = Map.of("LICENSE", bytes);
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class);
    }

    @Test void acceptsBundledWoff2Font() {
        byte[] bytes = ThemeTokens.resource("/themes/cyberpunk/assets/rajdhani-500.woff2");
        assertThat(ThemeAssets.validate(Map.of(FONT, bytes))).containsEntry(FONT, "font/woff2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "length", "flavor", "no-tables", "too-many-tables", "reserved", "expanded", "compressed"})
    void rejectsInconsistentWoff2Headers(String invalid) {
        byte[] bytes = ThemeTokens.resource("/themes/cyberpunk/assets/rajdhani-500.woff2");
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        switch (invalid) {
            case "signature" -> header.putInt(0, 0);
            case "length" -> header.putInt(8, bytes.length + 1);
            case "flavor" -> header.putInt(4, 0);
            case "no-tables" -> header.putShort(12, (short) 0);
            case "too-many-tables" -> header.putShort(12, (short) 257);
            case "reserved" -> header.putShort(14, (short) 1);
            case "expanded" -> header.putInt(16, 60 * 1024 * 1024 + 1);
            case "compressed" -> header.putInt(20, bytes.length - 47);
            default -> throw new AssertionError(invalid);
        }
        Map<String, byte[]> files = Map.of(FONT, bytes);
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class).hasMessageContaining("WOFF2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"VP8 ", "VP8L", "VP8X"})
    void recognizesWebpFrameAndCanvasDimensions(String kind) {
        byte[] bytes = kind.equals("VP8X") ? webp(chunk(kind, canvas(4096, 4096)), chunk("VP8L", lossless(1, 1)))
                : webp(chunk(kind, frame(kind, 4096, 4096)));
        assertThat(ThemeAssets.validate(Map.of(WEBP, bytes))).containsEntry(WEBP, "image/webp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALPH", "ICCP", "EXIF", "XMP "})
    void acceptsSupportedWebpMetadataAndOddChunkPadding(String kind) {
        byte[] bytes = webp(chunk(kind, new byte[] {1}), chunk("VP8L", lossless(1, 1)));
        assertThat(ThemeAssets.validate(Map.of(WEBP, bytes))).containsEntry(WEBP, "image/webp");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "riff", "length", "webp", "chunk-header", "chunk-length", "padding",
            "unsupported", "no-frame", "animated", "interframe", "keyframe-signature", "lossless-signature", "lossless-version"})
    void rejectsDamagedOrUnsupportedWebpContainers(String invalid) {
        byte[] bytes = invalidWebp(invalid);
        Map<String, byte[]> files = Map.of(WEBP, bytes);
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class).hasMessageContaining("WebP");
    }

    @ParameterizedTest
    @CsvSource({"VP8 ,0,1", "VP8 ,1,0", "VP8 ,4097,1", "VP8 ,1,4097",
            "VP8L,4097,1", "VP8L,1,4097", "VP8X,4097,1", "VP8X,1,4097"})
    void rejectsWebpFrameOrCanvasOutsideDimensionLimits(String kind, int width, int height) {
        String chunkKind = kind.equals("VP8") ? "VP8 " : kind;
        byte[] bytes = chunkKind.equals("VP8X")
                ? webp(chunk(chunkKind, canvas(width, height)), chunk("VP8L", lossless(1, 1)))
                : webp(chunk(chunkKind, frame(chunkKind, width, height)));
        Map<String, byte[]> files = Map.of(WEBP, bytes);
        assertThatThrownBy(() -> ThemeAssets.validate(files)).isInstanceOf(ThemeException.class).hasMessageContaining("4096");
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    private static byte[] invalidWebp(String invalid) {
        byte[] bytes = webp(chunk("VP8 ", frame("VP8 ", 1, 1)));
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return switch (invalid) {
            case "short" -> new byte[29];
            case "riff" -> { data.putInt(0, 0); yield bytes; }
            case "length" -> { data.putInt(4, -1); yield bytes; }
            case "webp" -> { data.putInt(8, 0); yield bytes; }
            case "chunk-header" -> webp(chunk("VP8 ", frame("VP8 ", 1, 1)), new byte[1]);
            case "chunk-length" -> { data.putInt(16, -1); yield bytes; }
            case "padding" -> webp(Arrays.copyOf(chunk("VP8 ", Arrays.copyOf(frame("VP8 ", 1, 1), 11)), 19));
            case "unsupported" -> webp(chunk("ANIM", new byte[10]));
            case "no-frame" -> webp(chunk("VP8X", canvas(1, 1)));
            case "animated" -> { byte[] canvas = canvas(1, 1); canvas[0] = 2; yield webp(chunk("VP8X", canvas)); }
            case "interframe" -> { bytes[20] = 1; yield bytes; }
            case "keyframe-signature" -> { bytes[23] = 0; yield bytes; }
            case "lossless-signature" -> webp(chunk("VP8L", new byte[10]));
            case "lossless-version" -> { byte[] frame = lossless(1, 1); frame[4] = 0x20; yield webp(chunk("VP8L", frame)); }
            default -> throw new AssertionError(invalid);
        };
    }

    private static byte[] webp(byte[]... chunks) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[12]);
        for (byte[] chunk : chunks) bytes.writeBytes(chunk);
        byte[] result = bytes.toByteArray();
        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, 0x46464952).putInt(4, result.length - 8).putInt(8, 0x50424557);
        return result;
    }

    private static byte[] chunk(String kind, byte[] payload) {
        ByteBuffer chunk = ByteBuffer.allocate(8 + payload.length + (payload.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        chunk.put(kind.getBytes(StandardCharsets.US_ASCII)).putInt(payload.length).put(payload);
        return chunk.array();
    }

    private static byte[] frame(String kind, int width, int height) {
        if (kind.equals("VP8L")) return lossless(width, height);
        ByteBuffer frame = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        frame.put(3, (byte) 0x9d).put(4, (byte) 0x01).put(5, (byte) 0x2a);
        frame.putShort(6, (short) width).putShort(8, (short) height);
        return frame.array();
    }

    private static byte[] lossless(int width, int height) {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0x2f)
                .putInt((width - 1) | ((height - 1) << 14)).array();
    }

    private static byte[] canvas(int width, int height) {
        ByteBuffer canvas = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        canvas.putInt(4, width - 1);
        canvas.put(7, (byte) (height - 1)).put(8, (byte) ((height - 1) >> 8)).put(9, (byte) ((height - 1) >> 16));
        return canvas.array();
    }
}
