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
        for (String job : List.of("jar", "test", "image", "e2e-chromium", "e2e-firefox", "e2e-webkit")) {
            assertThat(needs(job(jobs, job))).as("%s waits for checksum verification", job).contains("checksums");
            assertThat(job(jobs, job).get("if").toString()).doesNotContain("always()", "!cancelled()");
        }
    }

    @Test
    void checksumFailureBlocksTheRequiredGateAndAppearsInTheSummary() throws IOException {
        Map<String, Object> jobs = jobs();
        assertThat(needs(job(jobs, "ci-passed"))).contains("checksums");
        assertThat(needs(job(jobs, "pr-summary"))).contains("checksums");
        assertThat(job(jobs, "checksums").get("continue-on-error")).isNotEqualTo(true);
    }

    @Test
    void checksumPreparationRunsInsideCiInsteadOfAParallelWorkflow() {
        assertThat(Files.exists(Path.of(".github/workflows/dependency-checksums.yml"))).isFalse();
    }

    @Test
    void checksumGateVerifiesTheMergeCommitAndPatchesThePrHead() throws IOException {
        Map<String, Object> checksums = job(jobs(), "checksums");
        var steps = (List<?>) checksums.get("steps");
        var checkout = steps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> step.getOrDefault("uses", null) instanceof String uses && uses.startsWith("actions/checkout@"))
                .findFirst().orElseThrow();
        assertThat(((Map<?, ?>) checkout.get("with")).containsKey("ref")).isFalse();
        var patch = steps.stream().map(step -> (Map<?, ?>) step)
                .filter(step -> "patch".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat(patch.get("run").toString()).contains("git diff \"$DEPENDENCY_HEAD\" -- gradle/verification-metadata.xml");
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
