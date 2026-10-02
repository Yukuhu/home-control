package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one Dockerfile, and what CI and the HEALTHCHECK rely on in it. */
class DockerImageTest {

    @Test
    void theRuntimeTakesItsJarFromTheJarStage() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile).contains("\nFROM scratch AS jar\n");
        assertThat(runtime(dockerfile)).contains("\nCOPY --from=jar /*.jar /app/app.jar\n");
        assertThat(Path.of("Dockerfile.dist")).doesNotExist();
    }

    @Test
    void ciPassesTheJarItBuiltAsTheJarStage() throws Exception {
        // CI builds the jar once and hands it to every image build, which then skips Gradle. The tested build and
        // the build that pushes the tested image must be the same build.
        Map<String, Object> action = map(load(".github/actions/smoke-image/action.yml"), "runs");

        assertThat(stepsUsing(action, "docker/build-push-action@")).hasSize(2)
                .allSatisfy(step -> assertThat(map(step, "with"))
                        .containsEntry("context", ".")
                        .containsEntry("file", "Dockerfile")
                        .containsEntry("build-contexts", "jar=dist"));
    }

    @Test
    void theBuildStageUsesTheWrapperWithAGradleCache() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile).contains("\nENV GRADLE_USER_HOME=/gradle-home\n")
                .contains("\nRUN --mount=type=cache,target=/gradle-home ./gradlew --no-daemon bootJar\n");
    }

    @Test
    void theGradleTheWrapperDownloadsIsVerified() throws Exception {
        // A build from source downloads Gradle inside the image build; the wrapper checks it against this checksum,
        // as every other download of the build is checked (the base images by digest, the dependencies by
        // gradle/verification-metadata.xml).
        String properties = Files.readString(Path.of("gradle/wrapper/gradle-wrapper.properties"));

        assertThat(properties).containsPattern("(?m)^distributionSha256Sum=[0-9a-f]{64}$");
    }

    @Test
    void theImageChecksItsHealth() throws Exception {
        String runtime = runtime(Files.readString(Path.of("Dockerfile")));

        assertThat(runtime).contains("HEALTHCHECK ")
                .contains("/dev/tcp/127.0.0.1/${SERVER_PORT:-8080}")
                .contains("GET /health HTTP/1.0\\\\r\\\\nHost: 127.0.0.1\\\\r\\\\n");
    }

    private static String runtime(String dockerfile) {
        return dockerfile.substring(dockerfile.lastIndexOf("\nFROM "));
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
    private static List<Map<String, Object>> stepsUsing(Map<String, Object> job, String action) {
        return ((List<Map<String, Object>>) job.get("steps")).stream()
                .filter(step -> String.valueOf(step.get("uses")).startsWith(action)).toList();
    }
}
