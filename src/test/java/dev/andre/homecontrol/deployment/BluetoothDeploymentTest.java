package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BluetoothDeploymentTest {

    @Test
    void composeOverrideSwitchesTheModuleOnWithItsMounts() throws Exception {
        Map<String, Object> manifest = load("compose.bluetooth.yaml");
        Map<String, Object> service = map(map(manifest, "services"), "shield-remote");

        Map<String, Object> build = map(service, "build");
        assertThat(build.get("context")).isEqualTo(".");
        assertThat(map(build, "args").get("WITH_MPV")).isEqualTo("true");

        Map<String, Object> environment = map(service, "environment");
        assertThat(environment.get("HOME_CONTROL_BLUETOOTH_ENABLED")).isEqualTo("true");
        assertThat(environment.get("PULSE_SERVER")).isEqualTo("unix:/run/pulse/native");

        List<String> volumes = stringList(service, "volumes");
        assertThat(volumes).containsExactlyInAnyOrder(
                "/run/dbus:/run/dbus:ro",
                "/run/user/${HOST_AUDIO_UID:-1000}/pulse:/run/pulse");

        assertThat(service).doesNotContainKeys("network_mode", "image", "ports");
    }

    @Test
    void defaultComposeStaysFreeOfBluetooth() throws Exception {
        String text = Files.readString(Path.of("compose.yaml"));
        assertThat(text).doesNotContain("/run/dbus").doesNotContain("BLUETOOTH").doesNotContain("WITH_MPV");
    }

    @Test
    void casaOsVariantKeepsTheAppAndAddsTheMounts() throws Exception {
        Map<String, Object> manifest = load("casaos/docker-compose.bluetooth.yml");
        Map<String, Object> service = map(map(manifest, "services"), "shield-remote");

        assertThat(service.get("image")).isEqualTo("ghcr.io/yukuhu/home-control:latest-bluetooth");
        assertThat(service.get("network_mode")).isEqualTo("host");
        assertThat(service.get("restart")).isEqualTo("unless-stopped");

        Map<String, Object> environment = map(service, "environment");
        assertThat(environment.get("HOME_CONTROL_BLUETOOTH_ENABLED")).isEqualTo("true");
        assertThat(environment.get("PULSE_SERVER")).isEqualTo("unix:/run/pulse/native");

        List<Map<String, Object>> volumes = maps(service, "volumes");
        assertThat(volumes).hasSize(3);
        assertThat(volumes.get(0).get("source")).isEqualTo("/DATA/AppData/$AppID/data");
        assertThat(volumes.get(0).get("target")).isEqualTo("/data");
        assertThat(volumes.get(1).get("source")).isEqualTo("/run/dbus");
        assertThat(volumes.get(1).get("target")).isEqualTo("/run/dbus");
        assertThat(volumes.get(1).get("read_only")).isEqualTo(true);
        assertThat(volumes.get(2).get("source")).isEqualTo("/run/user/1000/pulse");
        assertThat(volumes.get(2).get("target")).isEqualTo("/run/pulse");

        Map<String, Object> serviceMetadata = map(service, "x-casaos");
        List<Map<String, Object>> metadataVolumes = maps(serviceMetadata, "volumes");
        List<Object> containers = metadataVolumes.stream().map(v -> v.get("container")).toList();
        assertThat(containers).containsExactlyInAnyOrder("/data", "/run/dbus", "/run/pulse");

        Map<String, Object> metadata = map(manifest, "x-casaos");
        assertThat(metadata.get("id")).isEqualTo("dev.andre.shield-remote");
        assertThat(metadata.get("main")).isEqualTo("shield-remote");
        assertThat(metadata.get("architectures")).isEqualTo(List.of("amd64", "arm64"));
    }

    @Test
    void casaOsDefaultStaysFreeOfBluetooth() throws Exception {
        String text = Files.readString(Path.of("casaos/docker-compose.yml"));
        assertThat(text).doesNotContain("/run/dbus").doesNotContain("latest-bluetooth");
    }

    @Test
    void imagesInstallMpvOnlyOnRequest() throws Exception {
        assertMpvOnlyAfterLastFrom(Path.of("Dockerfile"));
        assertMpvOnlyAfterLastFrom(Path.of("Dockerfile.dist"));
    }

    private static void assertMpvOnlyAfterLastFrom(Path path) throws Exception {
        String text = Files.readString(path);
        int lastFrom = text.lastIndexOf("FROM ");
        assertThat(lastFrom).as("%s has a FROM line", path).isGreaterThanOrEqualTo(0);
        String before = text.substring(0, lastFrom);
        String after = text.substring(lastFrom);
        assertThat(after).contains("ARG WITH_MPV=false");
        assertThat(after).contains("      && apt-get install -y --no-install-recommends mpv \\");
        assertThat(after).contains("if [ \"$WITH_MPV\" = \"true\" ]; then \\");
        assertThat(before).doesNotContain("mpv");
    }

    @Test
    void ciPublishesABluetoothVariant() throws Exception {
        Map<String, Object> workflow = load(".github/workflows/ci.yml");
        Map<String, Object> jobs = map(workflow, "jobs");

        Map<String, Object> release = map(jobs, "release");
        List<Map<String, Object>> releaseSteps = maps(release, "steps");
        Map<String, Object> metaBluetooth = releaseSteps.stream()
                .filter(step -> "meta-bluetooth".equals(step.get("id"))).findFirst().orElseThrow();
        Map<String, Object> metaWith = map(metaBluetooth, "with");
        assertThat((String) metaWith.get("flavor")).contains("suffix=-bluetooth");
        assertThat((String) metaWith.get("flavor")).contains("latest=false");

        Map<String, Object> buildBluetooth = releaseSteps.stream()
                .filter(step -> "Build and push the Bluetooth variant".equals(step.get("name"))).findFirst().orElseThrow();
        Map<String, Object> buildWith = map(buildBluetooth, "with");
        assertThat(buildWith.get("build-args")).isEqualTo("WITH_MPV=true");
        assertThat(buildWith.get("platforms")).isEqualTo("linux/amd64,linux/arm64");
        assertThat(buildWith.get("tags")).isEqualTo("${{ steps.meta-bluetooth.outputs.tags }}");

        Map<String, Object> image = map(jobs, "image");
        List<Map<String, Object>> imageSteps = maps(image, "steps");
        Map<String, Object> imageBluetooth = imageSteps.stream()
                .filter(step -> "Build Dockerfile with mpv".equals(step.get("name"))).findFirst().orElseThrow();
        Map<String, Object> imageWith = map(imageBluetooth, "with");
        assertThat(imageWith.get("build-args")).isEqualTo("WITH_MPV=true");
        assertThat(imageWith.get("push")).isEqualTo(false);
    }

    @Test
    void hostDocumentationCoversTheChecklist() throws Exception {
        String text = Files.readString(Path.of("docs/bluetooth-speakers.md"));
        assertThat(text).contains("/run/dbus:/run/dbus:ro")
                .contains("systemctl enable --now bluetooth")
                .contains("rfkill unblock bluetooth")
                .contains("loginctl enable-linger")
                .contains("PULSE_SERVER=unix:/run/pulse/native")
                .contains("monitor.bluez.seat-monitoring")
                .contains("with-logind")
                .contains("WITH_MPV=true")
                .contains("latest-bluetooth")
                .contains("HOME_CONTROL_BLUETOOTH_ENABLED")
                .contains("bluetoothctl")
                .contains("A2DP");
    }

    private static Map<String, Object> load(String path) throws Exception {
        try (InputStream input = Files.newInputStream(Path.of(path))) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> parent, String key) {
        return (List<String>) parent.get(key);
    }
}
