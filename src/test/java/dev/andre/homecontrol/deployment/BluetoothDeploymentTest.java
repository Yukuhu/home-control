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
        assertThat(build).containsEntry("context", ".");
        assertThat(map(build, "args")).containsEntry("WITH_MPV", "true");

        Map<String, Object> environment = map(service, "environment");
        assertThat(environment).containsEntry("HOME_CONTROL_BLUETOOTH_ENABLED", "true");
        assertThat(environment).containsEntry("PULSE_SERVER", "unix:/run/pulse/native");

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

        assertThat(service).containsEntry("image", "ghcr.io/yukuhu/home-control:latest-bluetooth");
        assertThat(service).containsEntry("network_mode", "host");
        assertThat(service).containsEntry("restart", "unless-stopped");

        Map<String, Object> environment = map(service, "environment");
        assertThat(environment).containsEntry("HOME_CONTROL_BLUETOOTH_ENABLED", "true");
        assertThat(environment).containsEntry("PULSE_SERVER", "unix:/run/pulse/native");

        List<Map<String, Object>> volumes = maps(service, "volumes");
        assertThat(volumes).hasSize(3);
        assertThat(volumes.get(0)).containsEntry("source", "/DATA/AppData/$AppID/data");
        assertThat(volumes.get(0)).containsEntry("target", "/data");
        assertThat(volumes.get(1)).containsEntry("source", "/run/dbus");
        assertThat(volumes.get(1)).containsEntry("target", "/run/dbus");
        assertThat(volumes.get(1)).containsEntry("read_only", true);
        assertThat(volumes.get(2)).containsEntry("source", "/run/user/1000/pulse");
        assertThat(volumes.get(2)).containsEntry("target", "/run/pulse");

        Map<String, Object> serviceMetadata = map(service, "x-casaos");
        List<Map<String, Object>> metadataVolumes = maps(serviceMetadata, "volumes");
        List<Object> containers = metadataVolumes.stream().map(v -> v.get("container")).toList();
        assertThat(containers).containsExactlyInAnyOrder("/data", "/run/dbus", "/run/pulse");

        Map<String, Object> metadata = map(manifest, "x-casaos");
        assertThat(metadata).containsEntry("id", "dev.andre.shield-remote");
        assertThat(metadata).containsEntry("main", "shield-remote");
        assertThat(metadata).containsEntry("architectures", List.of("amd64", "arm64"));
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
        assertThat((String) metaWith.get("flavor")).contains("suffix=-bluetooth").contains("latest=false");

        Map<String, Object> buildBluetooth = releaseSteps.stream()
                .filter(step -> "Build and push the Bluetooth variant".equals(step.get("name"))).findFirst().orElseThrow();
        Map<String, Object> buildWith = map(buildBluetooth, "with");
        assertThat(buildWith).containsEntry("build-args", "WITH_MPV=true");
        assertThat(buildWith).containsEntry("platforms", "linux/amd64,linux/arm64");
        assertThat(buildWith).containsEntry("tags", "${{ steps.meta-bluetooth.outputs.tags }}");

        Map<String, Object> image = map(jobs, "image");
        List<Map<String, Object>> imageSteps = maps(image, "steps");
        Map<String, Object> imageBluetooth = imageSteps.stream()
                .filter(step -> "Build Dockerfile with mpv".equals(step.get("name"))).findFirst().orElseThrow();
        Map<String, Object> imageWith = map(imageBluetooth, "with");
        assertThat(imageWith).containsEntry("build-args", "WITH_MPV=true");
        assertThat(imageWith).containsEntry("push", false);
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

    @Test
    void hostDocumentationCoversEveryFailureMode() throws Exception {
        String text = Files.readString(Path.of("docs/bluetooth-speakers.md"));
        assertThat(text).contains("## Failure modes")
                .contains("No D-Bus system socket")
                .contains("BlueZ is not running on the host")
                .contains("refused this container")
                .contains("No Bluetooth adapter found")
                .contains("powered off")
                .contains("mpv is not installed")
                .contains("No PipeWire or PulseAudio server is reachable")
                .contains("refused pairing")
                .contains("br-connection-profile-unavailable")
                .contains("did not answer")
                .contains("No audio output for")
                .contains("could not play the stream")
                .contains("plays audio only")
                .contains("not paired with this server any more")
                .contains("Nothing is playing");
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
