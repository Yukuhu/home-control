package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** CI builds and tests nothing for a pull request that scripts/code-changed.sh calls documentation only. */
class ChangedFilesTest {

    @Test
    void documentationAloneBuildsNothing() throws Exception {
        assertThat(codeChanged("docs/user/devices.md\nREADME.md\nLICENSE\n")).isEqualTo("false");
    }

    @Test
    void anythingElseOrNothingKnownIsCode() throws Exception {
        assertThat(codeChanged("docs/user/devices.md\nsrc/main/java/dev/andre/homecontrol/HomeControlApplication.java\n"))
                .isEqualTo("true");
        assertThat(codeChanged("")).isEqualTo("true");
    }

    /** A guide the deployment tests read is one of their inputs; a change to it alone must run them. */
    @Test
    void theGuidesTheTestsReadAreCode() throws Exception {
        List<String> guides = quoted("\"(docs/[^\"]+\\.md)\"", Path.of("build.gradle.kts"));

        assertThat(guides).isNotEmpty();
        for (String guide : guides) {
            assertThat(codeChanged(guide + "\n")).as(guide).isEqualTo("true");
        }
    }

    /** A test that starts reading a guide must make it an input of the test task, and so code for CI as well. */
    @Test
    void everyGuideATestReadsIsAnInputOfTheTests() throws Exception {
        List<String> read = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/test/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                read.addAll(quoted("Path\\.of\\(\"(docs/[^\"]+)\"\\)", source));
            }
        }

        assertThat(read).isNotEmpty();
        assertThat(quoted("\"(docs/[^\"]+\\.md)\"", Path.of("build.gradle.kts"))).containsAll(read);
    }

    private static List<String> quoted(String pattern, Path file) throws IOException {
        return Pattern.compile(pattern).matcher(Files.readString(file)).results().map(match -> match.group(1)).toList();
    }

    private static String codeChanged(String files) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "scripts/code-changed.sh").redirectErrorStream(true).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(files.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
