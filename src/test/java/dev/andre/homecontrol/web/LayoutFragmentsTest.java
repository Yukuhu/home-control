package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.FullAppTest;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.themes.ThemeCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every page shares its head, header and first-password fields through {@code fragments/layout.html}. */
class LayoutFragmentsTest extends FullAppTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Autowired MockMvc mvc;
    @Autowired ThemeCatalog themes;
    @Autowired DeviceRegistry devices;

    private static List<Path> templates() throws IOException {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(path -> path.toString().endsWith(".html")).sorted().toList();
        }
    }

    @Test
    void onlyTheLayoutCarriesTheSharedMarkup() throws IOException {
        for (Path template : templates()) {
            if (template.endsWith(Path.of("fragments", "layout.html"))) {
                continue;
            }
            assertThat(Files.readString(template)).as(template.toString())
                    .doesNotContain("<meta name=\"viewport\"")
                    .doesNotContain("class=\"app-header\"")
                    .doesNotContain("name=\"loginPasswordConfirmation\"");
        }
    }

    /** The shared head, then the page's own title, which checkers and readers look for in the page itself. */
    @ParameterizedTest
    @ValueSource(strings = {"dashboard.html", "setup.html", "login.html", "workflow-editor.html", "appearance.html", "offline.html"})
    void everyPageUsesTheSharedHeadAndNamesItself(String page) throws IOException {
        assertThat(Files.readString(TEMPLATES.resolve(page))).contains("fragments/layout :: pageHead(").contains("<title");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/setup", "/setup/appearance", "/offline.html"})
    void renderedPagesLoadTheDefaultFallbackBeforeTheCatalogAndSelectionScript(String path) throws Exception {
        // A fresh installation redirects Home to Setup until a device has been registered.
        devices.save(new Device("layout-test", "Layout test", DeviceKind.CAST, "127.0.0.1", Map.of(), Instant.now()));
        String html = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("data-theme=\"default\"");
        var fallback = Pattern.compile("<link(?=[^>]*data-theme-default)[^>]*>").matcher(html);
        assertThat(fallback.find()).isTrue();
        assertThat(fallback.group()).contains("href=\"" + themes.require("default").stylesheet() + "\"");
        int catalog = html.indexOf("src=\"/themes/catalog.js\"");
        int selection = html.indexOf("src=\"/js/theme.js\"");
        assertThat(catalog).isGreaterThan(fallback.start());
        assertThat(selection).isGreaterThan(catalog);
        if (!path.equals("/offline.html")) {
            var picker = Pattern.compile("<select(?=[^>]*data-theme-picker)[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(html);
            assertThat(picker.find()).isTrue();
            assertThat(Pattern.compile("<option[^>]*value=\"([^\"]+)\"").matcher(picker.group(1))
                    .results().map(option -> option.group(1)).toList())
                    .containsExactlyElementsOf(themes.themes().stream().map(theme -> theme.id()).toList());
        }
    }

    /** Like every fragment file, the layout is bare fragments, not a page of its own that would need a title. */
    @Test
    void theLayoutIsNotAPage() throws IOException {
        assertThat(Files.readString(TEMPLATES.resolve("fragments/layout.html")))
                .doesNotContain("<!DOCTYPE").doesNotContain("<html").doesNotContain("<head>").doesNotContain("<body>");
    }

    /** A placeholder that is replaced by a fragment is a th:block, never an empty link a checker takes for real. */
    @Test
    void noEmptyLinkStandsInForAFragment() throws IOException {
        for (Path template : templates()) {
            assertThat(Files.readString(template)).as(template.toString()).doesNotContain("<a th:replace");
        }
    }
}
