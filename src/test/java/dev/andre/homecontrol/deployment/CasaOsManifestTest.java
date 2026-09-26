package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CasaOsManifestTest {

    @Test
    void declaresPersistentHostNetworkedShieldRemote() throws Exception {
        Map<String, Object> manifest;
        try (InputStream input = Files.newInputStream(Path.of("casaos/docker-compose.yml"))) {
            manifest = new Yaml().load(input);
        }

        Map<String, Object> service = map(map(manifest, "services"), "shield-remote");
        assertThat(service).containsEntry("image", "ghcr.io/yukuhu/home-control:latest");
        assertThat(service).containsEntry("network_mode", "host");
        assertThat(service).containsEntry("restart", "unless-stopped");
        assertThat(service).doesNotContainKeys("ports", "environment");

        List<Map<String, Object>> volumes = maps(service, "volumes");
        assertThat(volumes).singleElement().satisfies(volume -> {
            assertThat(volume).containsEntry("type", "bind");
            assertThat(volume).containsEntry("source", "/DATA/AppData/$AppID/data");
            assertThat(volume).containsEntry("target", "/data");
        });

        Map<String, Object> serviceMetadata = map(service, "x-casaos");
        assertThat(maps(serviceMetadata, "ports")).singleElement().satisfies(port ->
                assertThat(port).containsEntry("container", "8080"));
        assertThat(maps(serviceMetadata, "volumes")).singleElement().satisfies(volume ->
                assertThat(volume).containsEntry("container", "/data"));

        Map<String, Object> metadata = map(manifest, "x-casaos");
        assertThat(metadata).containsEntry("id", "dev.andre.shield-remote");
        assertThat(metadata).containsEntry("main", "shield-remote");
        assertThat(metadata).containsEntry("index", "/");
        assertThat(metadata).containsEntry("port_map", "8080");
        assertThat(metadata).containsEntry("scheme", "http");
        assertThat(metadata).containsEntry("category", "Home");
        assertThat(metadata).containsEntry("architectures", List.of("amd64", "arm64"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.get(key);
    }
}
