package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.*;
import static dev.andre.homecontrol.themes.ThemeTestPackages.*;

class ThemeArchiveTest {
    @TempDir Path data;

    @ParameterizedTest
    @ValueSource(strings = {"default", "cyberpunk"})
    void protectsBothBuiltinsAndExportsEditableSource(String id) {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        byte[] exported = catalog.export(id);
        assertThatThrownBy(() -> catalog.inspect(exported)).isInstanceOf(ThemeException.class)
                .hasMessageContaining("ID");
        ThemePackage derivative = catalog.inspect(rename(exported, "new-" + id));
        assertThat(derivative.manifest().id()).isEqualTo("new-" + id);
        assertThat(derivative.files().get("tokens.json")).isNotEmpty();
        assertThatThrownBy(() -> catalog.remove(id)).isInstanceOf(ThemeException.class);
        assertThat(catalog.require(id).builtIn()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "/absolute", "assets/../file.png", "assets//file.png", "assets/./file.png", "assets\\file.png", "assets/%2e%2e.png", "assets/code.svg", "index.html", "assets/evil.js", "assets/a.zip"})
    void rejectsNoncanonicalAndUnsupportedArchivePaths(String path) {
        var files = files("custom", null);
        files.put(path, new byte[] {1, 2, 3});
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        byte[] archive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(archive)).isInstanceOf(ThemeException.class);
        assertThat(data.resolve("outside")).doesNotExist();
    }

    @Test void revisionIgnoresZipOrderAndExportHasOnlySource() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        var files = files("custom", ":root { color: #123456; }");
        var reversed = new LinkedHashMap<String, byte[]>();
        files.entrySet().stream().toList().reversed().forEach(e -> reversed.put(e.getKey(), e.getValue()));
        var first = catalog.inspect(zip(files));
        assertThat(catalog.inspect(zip(reversed)).revision()).isEqualTo(first.revision());
        catalog.install(zip(files), null);
        assertThat(unzip(catalog.export("custom"))).containsEntry("theme.css", files.get("theme.css"));
        assertThat(new String(catalog.export("custom"), StandardCharsets.ISO_8859_1)).doesNotContain("/data/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"bg\":\"red; color:blue\"}", "{\"unknown\":\"red\"}", "{\"bg\":42}", "{\"bg\":\"rgb(banana)\"}", "{\"bg\":\"rgba(1, 2, 3)\"}", "{\"radius-control\":\"calc(banana)\"}", "{\"radius-control\":\"clamp(1px, 2px)\"}", "{\"bg\":\"url(https://evil.test/x)\"}", "[]"})
    void rejectsMalformedOrUnsafeTokens(String json) {
        var files = files("custom", null);
        files.put("tokens.json", json.getBytes(StandardCharsets.UTF_8));
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        byte[] archive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(archive)).isInstanceOf(ThemeException.class);
    }

    @Test void enforcesDocumentAndEntryLimits() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        var oversized = files("custom", " ".repeat(256 * 1024 + 1));
        byte[] oversizedArchive = zip(oversized);
        assertThatThrownBy(() -> catalog.inspect(oversizedArchive)).isInstanceOf(ThemeException.class)
                .satisfies(e -> assertThat(((ThemeException) e).status()).isEqualTo(413));
        var files = files("custom", null);
        for (int i = 0; i < 256; i++) files.put("assets/license-" + i + ".txt", new byte[0]);
        byte[] manyEntries = zip(files);
        assertThatThrownBy(() -> catalog.inspect(manyEntries)).isInstanceOf(ThemeException.class);
    }
    @Test void rejectsDuplicateJsonFieldsTrailingValuesAndIntegerOverflow() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        for (String json : new String[] {"{\"bg\":\"red\",\"bg\":\"blue\"}", "{} {}"}) {
            var files = files("custom", null);
            files.put("tokens.json", json.getBytes(StandardCharsets.UTF_8));
            byte[] archive = zip(files);
            assertThatThrownBy(() -> catalog.inspect(archive)).isInstanceOf(ThemeException.class);
        }
        var files = files("custom", null);
        files.put("theme.json", new String(files.get("theme.json"), StandardCharsets.UTF_8)
                .replace("\"formatVersion\":1", "\"formatVersion\":4294967297").getBytes(StandardCharsets.UTF_8));
        byte[] archive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(archive)).isInstanceOf(ThemeException.class);
    }

    @Test void rewritesLocalImagesAndRejectsDisguisedOrUnusedBinaryAssets() throws Exception {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        var files = files("custom", ":root { --picture: url(assets/picture.png); & .tile { background: var(--picture); } }");
        files.put("assets/picture.png", bytes.toByteArray());
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        var installed = catalog.install(zip(files), null);
        String css = new String(catalog.asset(installed.stylesheet()).orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertThat(css).contains("/themes/packages/custom/" + installed.revision() + "/assets/picture.png");
        assertThat(installed.assets()).hasSize(2);
        files.remove("theme.css");
        byte[] unreferencedArchive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(unreferencedArchive)).isInstanceOf(ThemeException.class).hasMessageContaining("not referenced");
        files.put("theme.css", ":root { background: url(assets/picture.png); }".getBytes(StandardCharsets.UTF_8));
        files.put("assets/picture.png", "<svg/>".getBytes(StandardCharsets.UTF_8));
        byte[] disguisedArchive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(disguisedArchive)).isInstanceOf(ThemeException.class);
    }

    @Test void rejectsImagesExceedingDecodedDimensionsAndMalformedWoff2() throws Exception {
        var image = new java.awt.image.BufferedImage(4097, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        var files = files("custom", null);
        files.put("preview.png", bytes.toByteArray());
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        byte[] oversizedArchive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(oversizedArchive)).isInstanceOf(ThemeException.class).hasMessageContaining("4096");
        files.remove("preview.png");
        files.put("theme.css", "@font-face { font-family: theme-test; src: url(assets/font.woff2); }".getBytes(StandardCharsets.UTF_8));
        files.put("assets/font.woff2", new byte[] {'w', 'O', 'F', '2'});
        byte[] invalidFontArchive = zip(files);
        assertThatThrownBy(() -> catalog.inspect(invalidFontArchive)).isInstanceOf(ThemeException.class).hasMessageContaining("WOFF2");
    }

}
