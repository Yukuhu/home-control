package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Content-Security-Policy allows scripts only from this server, so no page may carry script of its own: no
 * {@code <script>} without {@code src}, no {@code on…=} handler attribute (Thymeleaf's {@code th:on…} included), no
 * {@code hx-on} and no {@code javascript:} link. Each page is read whole, so a tag spanning several lines is found too.
 */
class InlineCodeTest {

    private static final Pattern INLINE_CODE = Pattern.compile(String.join("|",
            "<script(?![^>]*\\bsrc\\s*=)[^>]*>",
            "[\\s:\"']on[a-z]+\\s*=",
            "hx-on",
            "[\\s\"'=]javascript:"), Pattern.CASE_INSENSITIVE);

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
            String text = Files.readString(page);
            Matcher found = INLINE_CODE.matcher(text);
            while (found.find()) {
                String code = found.group().strip();
                int at = text.indexOf(code, found.start());
                long line = text.chars().limit(at).filter(c -> c == '\n').count() + 1;
                findings.add(page + ":" + line + ": " + code);
            }
        }

        assertThat(findings).isEmpty();
    }
}
