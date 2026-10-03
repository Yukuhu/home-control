package dev.andre.homecontrol.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

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
        List<String> guides = Pattern.compile("\"(docs/[^\"]+\\.md)\"")
                .matcher(Files.readString(Path.of("build.gradle.kts"))).results().map(match -> match.group(1)).toList();

        assertThat(guides).isNotEmpty();
        for (String guide : guides) {
            assertThat(codeChanged(guide + "\n")).as(guide).isEqualTo("true");
        }
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
