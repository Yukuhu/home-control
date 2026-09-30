package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Content-Security-Policy allows scripts only from this server, so no page may carry script of its own: no
 * {@code <script>} without {@code src}, no {@code on…=} handler attribute, and no {@code hx-on}.
 */
class InlineCodeTest {

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>");
    private static final Pattern HANDLER = Pattern.compile("\\son[a-z]+=");
    private static final Pattern HX_ON = Pattern.compile("hx-on");

    private static List<Path> pages() throws IOException {
        try (Stream<Path> templates = Files.walk(Path.of("src/main/resources/templates"));
             Stream<Path> statics = Files.list(Path.of("src/main/resources/static"))) {
            return Stream.concat(templates, statics).filter(path -> path.toString().endsWith(".html")).sorted().toList();
        }
    }

    @Test
    void noPageCarriesInlineScriptOrHandlers() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path page : pages()) {
            List<String> lines = Files.readAllLines(page);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (INLINE_SCRIPT.matcher(line).find() || HANDLER.matcher(line).find() || HX_ON.matcher(line).find()) {
                    findings.add(page + ":" + (i + 1) + ": " + line.strip());
                }
            }
        }

        assertThat(findings).isEmpty();
    }
}
