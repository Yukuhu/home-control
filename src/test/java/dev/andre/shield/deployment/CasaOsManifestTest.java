package dev.andre.shield.deployment;

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
        assertThat(service.get("image")).isEqualTo("ghcr.io/yukuhu/home-control:latest");
        assertThat(service.get("network_mode")).isEqualTo("host");
        assertThat(service.get("restart")).isEqualTo("unless-stopped");
        assertThat(service).doesNotContainKeys("ports", "environment");

        List<Map<String, Object>> volumes = maps(service, "volumes");
        assertThat(volumes).singleElement().satisfies(volume -> {
            assertThat(volume.get("type")).isEqualTo("bind");
            assertThat(volume.get("source")).isEqualTo("/DATA/AppData/$AppID/data");
            assertThat(volume.get("target")).isEqualTo("/data");
        });

        Map<String, Object> serviceMetadata = map(service, "x-casaos");
        assertThat(maps(serviceMetadata, "ports")).singleElement().satisfies(port ->
                assertThat(port.get("container")).isEqualTo("8080"));
        assertThat(maps(serviceMetadata, "volumes")).singleElement().satisfies(volume ->
                assertThat(volume.get("container")).isEqualTo("/data"));

        Map<String, Object> metadata = map(manifest, "x-casaos");
        assertThat(metadata.get("id")).isEqualTo("dev.andre.shield-remote");
        assertThat(metadata.get("main")).isEqualTo("shield-remote");
        assertThat(metadata.get("index")).isEqualTo("/");
        assertThat(metadata.get("port_map")).isEqualTo("8080");
        assertThat(metadata.get("scheme")).isEqualTo("http");
        assertThat(metadata.get("category")).isEqualTo("Home");
        assertThat(metadata.get("architectures")).isEqualTo(List.of("amd64", "arm64"));
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
