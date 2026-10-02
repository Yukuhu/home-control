package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.config.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayInputStream;

final class ThemeTestPackages {
    private ThemeTestPackages() { }

    static Map<String, byte[]> files(String id, String css) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("theme.json", ("{\"formatVersion\":1,\"themeApiVersion\":1,\"id\":\"" + id
                + "\",\"name\":\"Test theme\",\"version\":\"1\",\"author\":\"Author\",\"description\":\"Description\",\"license\":\"MIT\"}").getBytes(StandardCharsets.UTF_8));
        files.put("tokens.json", "{}".getBytes(StandardCharsets.UTF_8));
        files.put("LICENSE", "MIT license".getBytes(StandardCharsets.UTF_8));
        if (css != null) files.put("theme.css", css.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    static byte[] zip(Map<String, byte[]> files) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (var file : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException e) { throw new AssertionError(e); }
    }

    static Map<String, byte[]> unzip(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            Map<String, byte[]> result = new LinkedHashMap<>();
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) result.put(entry.getName(), zip.readAllBytes());
            return result;
        } catch (IOException e) { throw new AssertionError(e); }
    }

    static byte[] rename(byte[] bytes, String id) {
        Map<String, byte[]> files = unzip(bytes);
        var manifest = (tools.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(files.get("theme.json"));
        manifest.put("id", id);
        files.put("theme.json", Json.MAPPER.writeValueAsBytes(manifest));
        return zip(files);
    }
}
