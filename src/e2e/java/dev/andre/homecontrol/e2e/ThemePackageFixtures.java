package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.config.Json;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Derivatives of real exports exercise the public authoring workflow, including bundled assets. */
final class ThemePackageFixtures {
    private ThemePackageFixtures() { }

    static byte[] derivative(byte[] exported, String id, String name, String background) throws IOException {
        return derivative(exported, id, name, background, null);
    }

    static byte[] derivative(byte[] exported, String id, String name, String background, String css) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(exported))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                if (!entry.isDirectory()) files.put(entry.getName(), input.readAllBytes());
            }
        }
        ObjectNode manifest = (ObjectNode) Json.MAPPER.readTree(files.get("theme.json"));
        manifest.put("id", id);
        manifest.put("name", name);
        files.put("theme.json", Json.MAPPER.writeValueAsBytes(manifest));
        ObjectNode tokens = (ObjectNode) Json.MAPPER.readTree(files.get("tokens.json"));
        tokens.put("bg", background);
        tokens.put("theme-color", background);
        files.put("tokens.json", Json.MAPPER.writeValueAsBytes(tokens));
        if (css != null) files.put("theme.css", css.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
