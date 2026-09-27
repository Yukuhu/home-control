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
        assertThat(map(build, "args")).containsEntry("WITH_MPV", "true").containsEntry("RUN_AS", "0:0");

        Map<String, Object> environment = map(service, "environment");
        assertThat(environment)
                .containsEntry("HOME_CONTROL_BLUETOOTH_ENABLED", "true")
                .containsEntry("PULSE_SERVER", "unix:/run/pulse/native");

        List<String> volumes = stringList(service, "volumes");
        assertThat(volumes).containsExactlyInAnyOrder(
                "/run/dbus:/run/dbus:ro",
                "/etc/machine-id:/etc/machine-id:ro",
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

        assertThat(service)
                .containsEntry("image", "ghcr.io/yukuhu/home-control:latest-bluetooth")
                .containsEntry("network_mode", "host")
                .containsEntry("restart", "unless-stopped");

        Map<String, Object> environment = map(service, "environment");
        assertThat(environment)
                .containsEntry("HOME_CONTROL_BLUETOOTH_ENABLED", "true")
                .containsEntry("PULSE_SERVER", "unix:/run/pulse/native");

        List<Map<String, Object>> volumes = maps(service, "volumes");
        assertThat(volumes).hasSize(4);
        assertThat(volumes.get(0)).containsEntry("source", "/DATA/AppData/$AppID/data");
        assertThat(volumes.get(0)).containsEntry("target", "/data");
        assertThat(volumes.get(1)).containsEntry("source", "/run/dbus");
        assertThat(volumes.get(1)).containsEntry("target", "/run/dbus");
        assertThat(volumes.get(1)).containsEntry("read_only", true);
        assertThat(volumes.get(2)).containsEntry("source", "/etc/machine-id");
        assertThat(volumes.get(2)).containsEntry("target", "/etc/machine-id");
        assertThat(volumes.get(2)).containsEntry("read_only", true);
        assertThat(volumes.get(3)).containsEntry("source", "/run/user/1000/pulse");
        assertThat(volumes.get(3)).containsEntry("target", "/run/pulse");

        Map<String, Object> serviceMetadata = map(service, "x-casaos");
        List<Map<String, Object>> metadataVolumes = maps(serviceMetadata, "volumes");
        List<Object> containers = metadataVolumes.stream().map(v -> v.get("container")).toList();
        assertThat(containers).containsExactlyInAnyOrder("/data", "/run/dbus", "/etc/machine-id", "/run/pulse");

        Map<String, Object> metadata = map(manifest, "x-casaos");
        assertThat(metadata)
                .containsEntry("id", "dev.andre.shield-remote")
                .containsEntry("main", "shield-remote")
                .containsEntry("architectures", List.of("amd64", "arm64"));
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
    void ciSmokeTestsTheBluetoothVariantOnEveryRun() throws Exception {
        Map<String, Object> jobs = map(load(".github/workflows/ci.yml"), "jobs");

        Map<String, Object> smoke = map(jobs, "smoke-bluetooth");
        assertThat(smoke).doesNotContainKey("if");
        List<Object> architectures = maps(map(map(smoke, "strategy"), "matrix"), "include").stream()
                .map(entry -> entry.get("arch")).toList();
        assertThat(architectures).containsExactlyInAnyOrder("amd64", "arm64");
        assertThat(stepsUsing(smoke, "./.github/actions/smoke-image")).singleElement()
                .satisfies(step -> assertThat(map(step, "with")).containsEntry("bluetooth", true));

        Map<String, Object> action = map(load(".github/actions/smoke-image/action.yml"), "runs");
        assertThat(stepsUsing(action, "docker/build-push-action@")).hasSize(2)
                .allSatisfy(step -> assertThat(map(step, "with")).containsEntry("build-args",
                        "WITH_MPV=${{ inputs.bluetooth }}\nRUN_AS=${{ steps.names.outputs.run-as }}\n"));
    }

    /** Only root may talk to BlueZ on the host's D-Bus, so only this variant runs as root. */
    @Test
    void onlyTheBluetoothVariantRunsAsRoot() throws Exception {
        for (String dockerfile : List.of("Dockerfile", "Dockerfile.dist")) {
            String text = Files.readString(Path.of(dockerfile));
            String runtime = text.substring(text.lastIndexOf("FROM "));
            assertThat(runtime).as(dockerfile).contains("ARG RUN_AS=1000:1000\n").contains("\nUSER ${RUN_AS}\n");
            assertThat(runtime.indexOf("chown \"$RUN_AS\" /data")).as("%s hands /data over before declaring the volume", dockerfile)
                    .isBetween(0, runtime.indexOf("VOLUME /data"));
        }

        String names = maps(map(load(".github/actions/smoke-image/action.yml"), "runs"), "steps").stream()
                .filter(step -> "names".equals(step.get("id"))).findFirst().orElseThrow().get("run").toString();
        assertThat(names).contains("run_as=1000:1000\nif [[ \"$BLUETOOTH\" == true ]]; then\n  variant=bluetooth\n  run_as=0:0\n");

        assertThat(map(map(load("compose.yaml"), "services"), "shield-remote")).doesNotContainKey("user");
    }

    @Test
    void ciPublishesABluetoothVariant() throws Exception {
        Map<String, Object> jobs = map(load(".github/workflows/ci.yml"), "jobs");
        assertThat(stringList(map(jobs, "release"), "needs")).doesNotContain("smoke-bluetooth");

        Map<String, Object> release = map(jobs, "release-bluetooth");
        assertThat(stringList(release, "needs")).contains("release", "smoke-bluetooth");
        assertThat(stepsUsing(release, "actions/download-artifact@")).singleElement()
                .satisfies(step -> assertThat(map(step, "with")).containsEntry("pattern", "digest-bluetooth-*"));

        List<Map<String, Object>> steps = maps(release, "steps");
        Map<String, Object> meta = steps.stream()
                .filter(step -> "meta".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat((String) map(meta, "with").get("flavor")).contains("suffix=-bluetooth").contains("latest=false");

        Map<String, Object> publish = steps.stream()
                .filter(step -> String.valueOf(step.get("run")).startsWith("scripts/publish-images.sh ")).findFirst().orElseThrow();
        assertThat(map(publish, "env")).containsEntry("TAGS", "${{ steps.meta.outputs.tags }}");
        assertThat((String) publish.get("run")).endsWith(" amd64,arm64");
    }

    @Test
    void hostDocumentationCoversTheChecklist() throws Exception {
        String text = Files.readString(Path.of("docs/user/bluetooth-speakers.md"));
        assertThat(text).contains("/run/dbus:/run/dbus:ro")
                .contains("/etc/machine-id:/etc/machine-id:ro")
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
        String text = Files.readString(Path.of("docs/user/bluetooth-speakers.md"));
        assertThat(text).contains("## Failure modes")
                .contains("No D-Bus system socket")
                .contains("The container has no D-Bus machine id")
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

    private static List<Map<String, Object>> stepsUsing(Map<String, Object> job, String action) {
        return maps(job, "steps").stream()
                .filter(step -> String.valueOf(step.get("uses")).startsWith(action)).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> parent, String key) {
        return (List<String>) parent.get(key);
    }
}
