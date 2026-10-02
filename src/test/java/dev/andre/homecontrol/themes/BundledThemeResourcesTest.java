package dev.andre.homecontrol.themes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BundledThemeResourcesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TOKEN_TYPES = Set.of("color", "length", "font", "number", "shadow", "paint", "text");

    @ParameterizedTest
    @ValueSource(strings = {"default", "cyberpunk"})
    void eachReferencePackageSuppliesEveryPublishedToken(String id) throws IOException {
        JsonNode schema = JSON.readTree(resource("token-schema.json"));
        JsonNode tokens = JSON.readTree(resource(id + "/tokens.json"));
        Set<String> names = new HashSet<>();
        schema.properties().forEach(property -> {
            names.add(property.getKey());
            assertThat(property.getKey()).doesNotStartWith("--");
            assertThat(property.getValue().asString()).isIn(TOKEN_TYPES);
            assertThat(tokens.has(property.getKey())).as("%s token %s", id, property.getKey()).isTrue();
        });
        assertThat(names).contains("theme-color", "bg", "surface", "fg", "accent", "danger", "font-body");
        tokens.properties().forEach(property -> {
            assertThat(names).contains(property.getKey());
            assertThat(property.getValue().isString()).as("%s token %s must be a string", id, property.getKey()).isTrue();
            assertThat(property.getValue().asString()).isNotBlank();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "cyberpunk"})
    void referencePackagesCarryPortableIdentityAndLicense(String id) throws IOException {
        JsonNode manifest = JSON.readTree(resource(id + "/theme.json"));
        assertThat(manifest.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(manifest.path("themeApiVersion").asInt()).isEqualTo(1);
        assertThat(manifest.path("id").asString()).isEqualTo(id);
        for (String field : Set.of("name", "version", "author", "description", "license")) {
            assertThat(manifest.path(field).asString()).as("%s metadata %s", id, field).isNotBlank();
        }
        assertThat(manifest.has("builtIn")).isFalse();
        assertThat(new String(resource(id + "/LICENSE"), StandardCharsets.UTF_8)).isNotBlank();
    }

    @Test
    void cyberpunkFontReferencesResolveInsideItsPortablePackage() throws IOException {
        String css = new String(resource("cyberpunk/theme.css"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("url\\(['\"]?([^)'\"]+)['\"]?\\)").matcher(css);
        Set<String> assets = new HashSet<>();
        while (matcher.find()) {
            String path = matcher.group(1);
            assertThat(path).startsWith("assets/").doesNotContain("..", ":", "\\");
            byte[] asset = resource("cyberpunk/" + path);
            assertThat(asset).hasSizeGreaterThan(4);
            assertThat(new String(asset, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("wOF2");
            assets.add(path);
        }
        assertThat(assets).containsExactlyInAnyOrder("assets/rajdhani-500.woff2", "assets/rajdhani-700.woff2");
        assertThat(new String(resource("cyberpunk/assets/OFL.txt"), StandardCharsets.UTF_8))
                .contains("Copyright (c) 2014 Indian Type Foundry", "This Font Software is licensed under the SIL Open Font License",
                        "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007");
    }

    private static byte[] resource(String path) throws IOException {
        return classpath("/themes/" + path);
    }

    private static byte[] classpath(String path) throws IOException {
        try (var input = BundledThemeResourcesTest.class.getResourceAsStream(path)) {
            assertThat(input).as("bundled resource %s", path).isNotNull();
            return input.readAllBytes();
        }
    }
}
