package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyVerificationWorkflowTest {
    @Test
    void strictGradleJobsWaitForChecksumVerification() throws IOException {
        Map<String, Object> jobs = jobs();
        for (String job : List.of("jar", "test", "image", "e2e-chromium", "e2e-firefox", "e2e-webkit", "dependencies")) {
            assertThat(needs(job(jobs, job))).as("%s waits for checksum verification", job).contains("checksums");
            assertThat(job(jobs, job).get("if").toString()).contains("needs.checksums.outputs.state == 'verified'").doesNotContain("always()", "!cancelled()");
        }
    }

    @Test
    void checksumFailureBlocksTheRequiredGateAndAppearsInTheSummary() throws IOException {
        Map<String, Object> jobs = jobs();
        assertThat(needs(job(jobs, "ci-passed"))).contains("checksums", "checksum-update");
        assertThat(needs(job(jobs, "pr-summary"))).contains("checksums", "checksum-update");
        assertThat(job(jobs, "checksums")).doesNotContainEntry("continue-on-error", true);
    }

    @Test
    void checksumPreparationRunsInsideCiInsteadOfAParallelWorkflow() {
        assertThat(Files.exists(Path.of(".github/workflows/dependency-checksums.yml"))).isFalse();
    }

    @Test
    void pythonCoverageReachesTheSonarJob() throws IOException {
        var checksumSteps = (List<?>) job(jobs(), "checksums").get("steps");
        var upload = checksumSteps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> step.get("with") instanceof Map<?, ?> with
                        && "checksum-coverage".equals(with.get("name")))
                .findFirst();
        assertThat(upload).as("checksums uploads its Python coverage report").isPresent();
        var report = (Map<?, ?>) upload.orElseThrow().get("with");
        assertThat(report.get("path")).isEqualTo("build/reports/dependency-checksums/coverage.xml");
        assertThat(report.get("if-no-files-found")).isEqualTo("error");
        var sonarSteps = (List<?>) job(jobs(), "sonar").get("steps");
        assertThat(sonarSteps.toString()).contains("checksum-coverage", "build/reports/dependency-checksums");
        assertThat(Files.readString(Path.of("build.gradle.kts")))
                .contains("sonar.python.coverage.reportPaths", "build/reports/dependency-checksums/coverage.xml");
    }

    @Test
    void checksumGateVerifiesTheMergeCommitAndPatchesThePrHead() throws IOException {
        Map<String, Object> checksums = job(jobs(), "checksums");
        var steps = (List<?>) checksums.get("steps");
        var checkout = steps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> step.getOrDefault("uses", null) instanceof String uses && uses.startsWith("actions/checkout@"))
                .findFirst().orElseThrow();
        assertThat(((Map<?, ?>) checkout.get("with")).containsKey("ref")).isFalse();
        assertThat(checksums.get("outputs")).isInstanceOf(Map.class);
        Map<String, Object> publisher = job(jobs(), "checksum-update");
        assertThat(needs(publisher)).contains("checksums");
        assertThat(publisher.get("if").toString()).contains("needs.checksums.outputs.state == 'candidate'");
        var publishingSteps = (List<?>) publisher.get("steps");
        var trusted = publishingSteps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> "trusted".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat(((Map<?, ?>) trusted.get("with")).get("ref"))
                .hasToString("${{ github.event.pull_request.base.sha }}");
        assertThat(checksums.toString()).doesNotContain("CHECKSUM_APP_PRIVATE_KEY", "create-github-app-token");
        assertThat(publisher.get("permissions").toString()).doesNotContain("write");
    }

    @Test
    void requiredGateOnlyPassesVerifiedCodeOrDocumentation() throws Exception {
        var steps = (List<?>) job(jobs(), "ci-passed").get("steps");
        String script = ((Map<?, ?>) steps.getLast()).get("run").toString();
        for (String state : List.of("verified", "candidate", "")) {
            for (String code : List.of("true", "false")) {
                String needs = """
                        {"changes":{"result":"success","outputs":{"code":"%s"}},
                         "checksums":{"result":"success","outputs":{"state":"%s"}},
                         "checksum-update":{"result":"skipped","outputs":{}}}
                        """.formatted(code, state);
                var process = new ProcessBuilder("bash", "-e", "-c", script).redirectErrorStream(true);
                process.environment().put("NEEDS_JSON", needs);
                Process running = process.start();
                String output = new String(running.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                int status = running.waitFor();
                assertThat(status).as("code=%s state=%s: %s", code, state, output)
                        .isEqualTo(code.equals("false") || state.equals("verified") ? 0 : 1);
            }
        }
    }

    private static Map<String, Object> jobs() throws IOException {
        try (var input = Files.newInputStream(Path.of(".github/workflows/ci.yml"))) {
            Map<String, Object> workflow = new Yaml().load(input);
            return job(workflow, "jobs");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(Map<String, Object> map, String name) {
        return (Map<String, Object>) map.get(name);
    }

    private static List<String> needs(Map<String, Object> job) {
        Object needs = job.get("needs");
        return needs instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(needs.toString());
    }
}
