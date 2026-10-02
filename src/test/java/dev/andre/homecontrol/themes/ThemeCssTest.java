package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static dev.andre.homecontrol.themes.ThemeTestPackages.*;

class ThemeCssTest {
    @TempDir Path data;

    @ParameterizedTest
    @ValueSource(strings = {
        "@import 'https://evil.test/x.css';",
        ":root { background: url(https://evil.test/a.png); }",
        ":root { --image: url(/api/private); background: var(--image); }",
        ":root { background: u\\72l(https://evil.test/a); }",
        ":root { background: image-set('https://evil.test/a' 1x); }",
        ":root { background: image-set(url(assets/missing.png) 1x); }",
        ":root { background: image('https://evil.test/a'); }",
        ":root { background: cross-fade(url(https://evil.test/a), red, 20%); }",
        ":root { --asset: 'https://evil.test/a'; background: image-set(var(--asset) 1x); }",
        ":root { background: attr(data-secret url); }",
        ":root { unknown-property: url(assets/a.png); }",
        ":root { color: red; broken declaration; }",
        "@unknown foo { a { color: red; } }",
        "@font-face { font-family: AppFont; src: local('Arial'); }",
        "@keyframes app-spinner { to { opacity: 0; } }",
        ":root { display: none; }",
        ":root { animation: app-spinner 1s infinite; }",
        ":root { background: calc(url(assets/missing.png)); }",
        ":root { & .tile { @supports (background: url(https://evil.test/a)) { color: red; } } }",
        ":root { color: red !important; }",
        ":root { & .tile { background: url(https://evil.test/a); } }",
        "@media (min-width: 20rem) { :root { background: url(//evil.test/a); } }",
        ":root[data-theme=default] { color: red; }",
        ":root:has(input[value*=secret]) { color: red; }"
    })
    void rejectsUnsupportedAndResourceBearingConstructsRecursively(String css) {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        assertThatThrownBy(() -> catalog.inspect(zip(files("custom", css)))).isInstanceOf(ThemeException.class);
    }

    @Test void compilesNestedRulesGradientsAndNamespacedAnimations() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        String css = ":root { --line: #123; & .tile:hover { background: linear-gradient(45deg, var(--line), transparent); "
                + "clip-path: polygon(0 0, calc(100% - 2px) 0, 100% 100%); } "
                + "@media (prefers-reduced-motion: no-preference) { & .brand { animation: theme-pulse 1s steps(2) infinite; } } } "
                + "@keyframes theme-pulse { to { opacity: .5; } }";
        var installed = catalog.install(zip(files("custom", css)), null);
        String compiled = new String(catalog.asset(installed.stylesheet()).orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertThat(compiled).contains("data-theme=\"custom\"", "linear-gradient", "theme-custom-");
        assertThat(compiled).doesNotContain("@import");
    }
    @Test void rejectsDeepCssBeforeTheParserEvenWhenCommentsContainClosingBraces() {
        for (String prefix : new String[] {"/*" + "}".repeat(6000) + "*/", ":root { --text: \"" + ")".repeat(6000) + "\"; }"}) {
            String css = prefix + " :root { color: " + "rgb(".repeat(5000) + "0" + ")".repeat(5000) + "; }";
            assertThatThrownBy(() -> new ThemeCss("custom", "0".repeat(64), java.util.Map.of()).compile(java.util.Map.of(), css))
                    .isInstanceOf(ThemeException.class).hasMessageContaining("nesting");
        }
    }

    @Test void rejectsDuplicateFontFamilyDescriptorsThatOverrideTheCheckedNamespace() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        var source = unzip(rename(catalog.export("cyberpunk"), "custom"));
        String css = new String(source.get("theme.css"), StandardCharsets.UTF_8);
        css = css.replace("font-family: \"theme-rajdhani\";", "font-family: \"theme-rajdhani\"; font-family: Inter;");
        source.put("theme.css", css.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> catalog.inspect(zip(source))).isInstanceOf(ThemeException.class).hasMessageContaining("Duplicate");
    }

}
