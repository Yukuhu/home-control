package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every page shares its head, header and first-password fields through {@code fragments/layout.html}. */
class LayoutFragmentsTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static List<Path> templates() throws IOException {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(path -> path.toString().endsWith(".html"))
                    .filter(path -> !path.endsWith(Path.of("fragments", "layout.html")))
                    .sorted().toList();
        }
    }

    @Test
    void onlyTheLayoutCarriesTheSharedMarkup() throws IOException {
        for (Path template : templates()) {
            assertThat(Files.readString(template)).as(template.toString())
                    .doesNotContain("<meta name=\"viewport\"")
                    .doesNotContain("class=\"app-header\"")
                    .doesNotContain("name=\"loginPasswordConfirmation\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dashboard.html", "setup.html", "login.html", "workflow-editor.html"})
    void everyPageUsesTheSharedHead(String page) throws IOException {
        assertThat(Files.readString(TEMPLATES.resolve(page))).contains("fragments/layout :: pageHead(");
    }
}
