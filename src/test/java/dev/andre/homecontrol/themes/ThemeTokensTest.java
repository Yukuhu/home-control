package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.config.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeTokensTest {
    private final ThemeTokens tokens = new ThemeTokens();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        bg | #123
        bg | #1234
        bg | #123456
        bg | #12345678
        bg | currentColor
        bg | rgb(10, 20, 30)
        bg | rgba(10, 20, 30, .5)
        bg | hsl(120, 50%, 50%)
        bg | hsla(120, 50%, 50%, .5)
        theme-color | #aAbBcC
        radius-control | 0
        radius-control | -.5rem
        radius-control | 12dvh
        radius-control | min(1px, 2rem)
        radius-control | max(1px, 2rem)
        radius-control | clamp(1px, 2vw, 3rem)
        radius-control | calc((100% - 2px) / 2)
        line-height-body | +1.5
        line-height-body | -.5
        font-body | Arial, sans-serif
        color-scheme | light dark
        font-placeholder-style | normal
        art-bg | red, #123, rgb(1, 2, 3)
        art-bg | linear-gradient(red, blue), var(--bg)
        drawer-shadow | none
        drawer-shadow | inset 1px -.5rem 2em 10% red, 0 0 #123
        drawer-shadow | 0 1px rgba(1, 2, 3, .5), var(--shadow)
        """)
    void acceptsValuesWithinTheirTokenType(String name, String value) {
        Map<String, String> values = tokens.read(input(name, value));
        assertThat(values).containsEntry(name, value).containsKey("surface");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        unknown | red
        bg | red blue
        bg | #12345
        bg | banana
        bg | var(--bg)
        bg | rgb(banana)
        bg | ١
        theme-color | red
        theme-color | #fff
        radius-control | 2
        radius-control | 1s
        radius-control | 1px 2px
        radius-control | calc(banana)
        radius-control | calc(var(--length))
        radius-control | clamp(1px, 2px)
        radius-control | clamp(1px, 2px, 3px, 4px)
        radius-control | min()
        radius-control | min(1px, banana)
        radius-control | rgb(1, 2, 3)
        line-height-body | 1px
        line-height-body | NaN
        line-height-body | ١
        font-body | var(--font)
        font-body | url(assets/font.woff2)
        color-scheme | light  dark
        color-scheme | light1
        color-scheme | Light
        color-scheme | -dark
        art-bg | banana
        art-bg | calc(1px)
        art-bg | rgb(banana)
        art-bg | 1px
        drawer-shadow | 1vh red
        drawer-shadow | linear-gradient(red, blue)
        drawer-shadow | rgb(banana)
        drawer-shadow | banana
        drawer-shadow | 1px / 2px
        """)
    void rejectsValuesOutsideTheirTokenType(String name, String value) {
        byte[] input = input(name, value);
        assertThatThrownBy(() -> tokens.read(input)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "red; color: blue", "red{", "red}", "red !important"})
    void rejectsBlankOrInjectedTokenValues(String value) {
        byte[] input = input("bg", value);
        assertThatThrownBy(() -> tokens.read(input)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "42", "{\"bg\":42}", "{\"bg\":null}", "{\"bg\":\"red\",\"bg\":\"blue\"}"})
    void requiresAnObjectWithUniqueStringValues(String json) {
        byte[] input = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> tokens.read(input)).isInstanceOf(ThemeException.class);
    }

    @Test void appliesDefaultsWhenThePackageOmitsTokens() {
        assertThat(tokens.read("{}".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("bg", "#101917").containsEntry("font-size-body", "15px");
    }

    @Test void validatesLongTextValuesWithoutRecursiveMatching() {
        String value = "a ".repeat(1023) + "a";
        assertThat(tokens.read(input("color-scheme", value))).containsEntry("color-scheme", value);
        byte[] malformed = input("color-scheme", value + "1");
        assertThatThrownBy(() -> tokens.read(malformed)).isInstanceOf(ThemeException.class);
    }

    @Test void rejectsValuesAboveTheLengthLimitAndNonFiniteNumbers() {
        byte[] longText = input("color-scheme", "a".repeat(2049));
        byte[] overflow = input("line-height-body", "9".repeat(400));
        assertThatThrownBy(() -> tokens.read(longText)).isInstanceOf(ThemeException.class);
        assertThatThrownBy(() -> tokens.read(overflow)).isInstanceOf(ThemeException.class);
    }

    @Test void missingBundledResourcesFailExplicitly() {
        assertThatThrownBy(() -> ThemeTokens.resource("/themes/missing.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing bundled");
    }

    private static byte[] input(String name, String value) {
        return Json.MAPPER.writeValueAsBytes(Map.of(name, value));
    }
}
