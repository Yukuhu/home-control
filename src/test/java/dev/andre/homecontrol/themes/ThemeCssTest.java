package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        byte[] archive = zip(files("custom", css));
        assertThatThrownBy(() -> catalog.inspect(archive)).isInstanceOf(ThemeException.class);
    }

    @Test void compilesNestedRulesGradientsAndNamespacedAnimations() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        String css = ":root { --line: #123; & .tile:hover { background: linear-gradient(45deg, var(--line), transparent); "
                + "clip-path: polygon(0 0, calc(100% - 2px) 0, 100% 100%); } "
                + "@media (prefers-reduced-motion: no-preference) { & .brand { animation: theme-pulse 1s steps(2) infinite; } } } "
                + "@keyframes theme-pulse { to { opacity: .5; } }";
        var installed = catalog.install(zip(files("custom", css)), null);
        String compiled = new String(catalog.asset(installed.stylesheet()).orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertThat(compiled).contains("data-theme=\"custom\"", "linear-gradient", "theme-custom-")
                .doesNotContain("@import");
    }
    @Test void rejectsDeepCssBeforeTheParserEvenWhenCommentsContainClosingBraces() {
        for (String prefix : new String[] {"/*" + "}".repeat(6000) + "*/", ":root { --text: \"" + ")".repeat(6000) + "\"; }"}) {
            String css = prefix + " :root { color: " + "rgb(".repeat(5000) + "0" + ")".repeat(5000) + "; }";
            ThemeCss compiler = compiler(Map.of());
            Map<String, String> tokens = Map.of();
            assertThatThrownBy(() -> compiler.compile(tokens, css))
                    .isInstanceOf(ThemeException.class).hasMessageContaining("nesting");
        }
    }

    @Test void rejectsDuplicateFontFamilyDescriptorsThatOverrideTheCheckedNamespace() {
        ThemeCatalog catalog = new ThemeCatalog(new DataDirectory(data));
        var source = unzip(rename(catalog.export("cyberpunk"), "custom"));
        String css = new String(source.get("theme.css"), StandardCharsets.UTF_8);
        css = css.replace("font-family: \"theme-rajdhani\";", "font-family: \"theme-rajdhani\"; font-family: Inter;");
        source.put("theme.css", css.getBytes(StandardCharsets.UTF_8));
        byte[] archive = zip(source);
        assertThatThrownBy(() -> catalog.inspect(archive)).isInstanceOf(ThemeException.class).hasMessageContaining("Duplicate");
    }


    @Test void preservesLayerScopeAndSortedTokenOutput() {
        ThemeCss compiler = compiler(Map.of());
        String compiled = compiler.compile(Map.of("z", "blue", "a", "red"), ".tile { color: red; }");
        assertThat(compiled).isEqualTo("""
                @layer theme {
                :root[data-theme="custom"] {
                --a: red;
                --z: blue;
                }
                :root[data-theme="custom"] .tile {
                color:red;
                }
                }
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ".tile:is(:hover, :focus)", ".tile:where(:hover, :focus)", ".tile:not(:disabled)",
        ".tile:has(> button)", "button:nth-child(2n + 1)", "button:nth-last-of-type(even)",
        "#search-q", "input[type=text]", "dialog[open]", "main > .tile + .tile ~ button"
    })
    void scopesSupportedSelectorForms(String selector) {
        String compiled = compile(selector + " { color: red; }");
        String firstMember = selector.startsWith("button") ? ":is(button,:where(label.sheet-device))"
                : selector.split("[: >+~]", 2)[0];
        assertThat(compiled).contains(":root[data-theme=\"custom\"] " + firstMember)
                .contains("color:red;");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        .sheet-device[aria-checked] | .sheet-device:is([aria-checked],:where(label.sheet-device[data-sheet-checked]))
        .sheet-device[aria-checked="true"] | .sheet-device:is([aria-checked="true"],:where(label.sheet-device[data-sheet-checked="true"]))
        .sheet-device[aria-checked="false"] | .sheet-device:is([aria-checked="false"],:where(label.sheet-device[data-sheet-checked="false"]))
        .sheet-device[role] | .sheet-device:is([role],:where(label.sheet-device))
        .sheet-device[role="radio"] | .sheet-device:is([role="radio"],:where(label.sheet-device))
        .sheet-device[role="RADIO" i] | .sheet-device:is([role="RADIO" i],:where(label.sheet-device))
        .sheet-device[role="radio" s] | .sheet-device:is([role="radio" s],:where(label.sheet-device))
        button.sheet-device[role="radio"][aria-checked="true"] | :is(button,:where(label.sheet-device)).sheet-device:is([role="radio"],:where(label.sheet-device)):is([aria-checked="true"],:where(label.sheet-device[data-sheet-checked="true"]))
        .sheet:has(button.sheet-device[role="radio"][aria-checked="true"]) | .sheet:has(:is(button,:where(label.sheet-device)).sheet-device:is([role="radio"],:where(label.sheet-device)):is([aria-checked="true"],:where(label.sheet-device[data-sheet-checked="true"])))
        """)
    void compilesLegacyPlaybackSelectorsWithSpecificityPreservingAliases(String source, String expected) {
        assertThat(compile(source + " { color: red; }"))
                .contains(":root[data-theme=\"custom\"] " + expected + " {\ncolor:red;");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ".sheet-device[role=\"checkbox\"]", ".sheet-device[role=\"RADIO\"]",
        ".sheet-device[role=\"RADIO\" s]", ".sheet-device[role=\"radıo\" i]"
    })
    void keepsRolesThatDoNotMatchRadioDistinctFromPlaybackCards(String selector) {
        assertThat(compile(selector + " { color: red; }"))
                .contains(":root[data-theme=\"custom\"] " + selector + " {\ncolor:red;")
                .doesNotContain("label.sheet-device");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "@namespace svg 'https://example.test/svg'; svg|path { color: red; }",
        "@media speech { button { color: red; } }",
        "@media (resolution: 2dppx) { button { color: red; } }",
        "@media (width >= 20rem) { button { color: red; } }",
        "button { :root { color: red; } }",
        ":is(:root, button) { color: red; }", "button :root { color: red; }",
        "& .tile { color: red; }", "#private { color: red; }", "unknown { color: red; }",
        "button:visited { color: red; }", "input[role*=admin] { color: red; }",
        "input[private|role] { color: red; }", "button:nth-child(2n of .tile) { color: red; }",
        "button:lang(en) { color: red; }",
        "button { @font-face { font-family: theme-test; src: url(assets/font.woff2); } }",
        "button { @keyframes theme-spin { to { opacity: 0; } } }",
        "@font-face { src: url(assets/font.woff2); }",
        "@font-face { font-family: theme-test; }",
        "@-webkit-keyframes theme-spin { to { opacity: 0; } }",
        "@keyframes theme-spin { 101% { opacity: 0; } }",
        "button { animation: theme-spin 1s var(--timing); }",
        "button { animation: theme-spin / 1s; }",
        "button { pointer-events: none; }",
        "button::before { pointer-events: auto; }",
        "button::before { content: 'overlay'; }",
        "button::before, button { width: 10px; }",
        "button { color: red; ] }", "button { color: red;", "button { color: 'red; }", "/* unterminated",
        "button { color: red; } */", "button { --text: '\0'; }"
    })
    void rejectsUnsupportedRulesSelectorsAndDecorations(String source) {
        assertThatThrownBy(() -> compile(source)).isInstanceOf(ThemeException.class);
    }

    @Test void compilesMediaDecorationsAndNumericMathWithoutLosingScoping() {
        String source = "@media screen and (min-width: 20rem), print { "
                + "button { position: relative; font-size: calc((100% - 2px) / 2); "
                + "&::before { content: ''; position: absolute; inset: 0; pointer-events: none; } } }";
        assertThat(compile(source)).contains("@media screen", "print", ":root[data-theme=\"custom\"] :is(button,:where(label.sheet-device))", "&::before", "pointer-events:none", "calc(");
    }

    @Test void namespacesFontAndAnimationReferencesAndRewritesLocalAssets() {
        ThemeCss compiler = compiler(Map.of("assets/font.woff2", "font/woff2", "assets/bg.png", "image/png", "preview.png", "image/png"));
        String source = "@font-face { font-family: 'theme-text'; src: url(assets/font.woff2) format('woff2'); } "
                + "button { font-family: 'theme-text'; background: url(assets/bg.png); animation: theme-spin 1s linear, none; } "
                + "@keyframes theme-spin { from, 25.5%, 100% { opacity: .5; } to { opacity: 1; } }";
        assertThat(compiler.compile(Map.of(), source))
                .contains("theme-custom-000000000000-text", "theme-custom-000000000000-spin", "25.5%", "100%")
                .contains("/themes/packages/custom/" + "0".repeat(64) + "/assets/font.woff2")
                .contains("/themes/packages/custom/" + "0".repeat(64) + "/assets/bg.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "url(assets/bg.png)", "url(assets/font.woff2) format('truetype')", "local('Arial')",
        "format('woff2')", "url(assets/font.woff2) / url(assets/font.woff2)", "var(--font)", "format()"
    })
    void rejectsNonWoff2OrIndirectFontSources(String value) {
        ThemeCss compiler = compiler(Map.of("assets/font.woff2", "font/woff2", "assets/bg.png", "image/png"));
        Map<String, String> tokens = Map.of();
        String source = "@font-face { font-family: theme-text; src: " + value + "; }";
        assertThatThrownBy(() -> compiler.compile(tokens, source)).isInstanceOf(ThemeException.class);
    }

    @Test void rejectsUnreferencedBinaryAssets() {
        ThemeCss compiler = compiler(Map.of("assets/bg.png", "image/png"));
        Map<String, String> tokens = Map.of();
        assertThatThrownBy(() -> compiler.compile(tokens, "")).isInstanceOf(ThemeException.class)
                .hasMessageContaining("not referenced");
    }

    @Test void rejectsRuleAndValueDepthEvenBelowTheParserLimit() {
        String rules = "@media screen {".repeat(34) + "button { color: red; }" + "}".repeat(34);
        String values = "button { color: " + "var(--color,".repeat(34) + "red" + ")".repeat(34) + "; }";
        assertThatThrownBy(() -> compile(rules)).isInstanceOf(ThemeException.class).hasMessageContaining("nesting");
        assertThatThrownBy(() -> compile(values)).isInstanceOf(ThemeException.class).hasMessageContaining("nested");
    }

    @Test void ignoresBracketsInsideCommentsAndQuotedValues() {
        String source = "/* ([{ */ button { --text: ')]}'; --other: \"/* ([{ \"; color: red; } /* end */";
        assertThat(compile(source)).contains("--text:", "--other:", "color:red;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"red; color: blue", "red !important", "red; } button { color: blue", "red; & .tile { color: blue; }"})
    void rejectsValuesThatEscapeTheirDeclaration(String value) {
        assertThatThrownBy(() -> ThemeCss.parseValue(value)).isInstanceOf(ThemeException.class);
    }

    private static String compile(String source) {
        return compiler(Map.of()).compile(Map.of(), source);
    }

    private static ThemeCss compiler(Map<String, String> contentTypes) {
        return new ThemeCss("custom", "0".repeat(64), contentTypes);
    }
}
