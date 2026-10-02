package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Releases and the changelog are made from commit messages, so CI checks every commit of a pull request. */
class CommitMessagesTest {

    @Test
    void conventionalSubjectsPass() throws Exception {
        Result result = check("""
                feat: GET /health answers ok without a login
                fix(web): the setup page keeps its scroll position
                build(deps): bump org.bouncycastle:bcprov-jdk18on from 1.85 to 1.86
                refactor!: core becomes a Gradle module of its own
                revert: feat: a feature that broke the build
                """);

        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test
    void everyOtherSubjectFailsTheCheckByName() throws Exception {
        Result result = check("""
                feat: this one is fine
                Add a thing
                feature: an unknown type
                fix:a missing space
                fix:\s
                Fix: a capital type
                """);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output())
                .contains("Not a Conventional Commit: Add a thing")
                .contains("Not a Conventional Commit: feature: an unknown type")
                .contains("Not a Conventional Commit: fix:a missing space")
                .contains("Not a Conventional Commit: fix: \n")
                .contains("Not a Conventional Commit: Fix: a capital type")
                .doesNotContain("this one is fine");
    }

    @Test
    void ciChecksEveryCommitOfAPullRequest() throws Exception {
        Map<String, Object> jobs = map(load(".github/workflows/ci.yml"), "jobs");
        Map<String, Object> commits = map(jobs, "commits");

        assertThat(commits).containsEntry("if", "github.event_name == 'pull_request'");
        assertThat(maps(commits, "steps")).anySatisfy(step -> assertThat(String.valueOf(step.get("run")))
                .contains("git log --no-merges --format=%s \"$BASE..$HEAD\" | scripts/check-commits.sh"));
        assertThat(objects(map(jobs, "ci-passed"), "needs")).contains("commits");
    }

    private static Result check(String subjects) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "scripts/check-commits.sh").redirectErrorStream(true).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(subjects.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {
    }

    private static Map<String, Object> load(String path) throws IOException {
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
    private static List<Object> objects(Map<String, Object> parent, String key) {
        return (List<Object>) parent.get(key);
    }
}
