package dev.andre.homecontrol.core.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourcePreferencesTest {

    @Test
    void defaultsAreEmptyWithTheGivenLocale() {
        SourcePreferences prefs = SourcePreferences.defaults("de-DE", "DE");

        assertThat(prefs.railOrder()).isEmpty();
        assertThat(prefs.hiddenRails()).isEmpty();
        assertThat(prefs.disabledSources()).isEmpty();
        assertThat(prefs.refreshMinutes()).isEmpty();
        assertThat(prefs.providers()).isEmpty();
        assertThat(prefs.locale()).isEqualTo("de-DE");
        assertThat(prefs.region()).isEqualTo("DE");
    }

    @Test
    void rejectsBadLocaleRegionProvidersAndIntervals() {
        SourcePreferences defaults = SourcePreferences.defaults("de-DE", "DE");

        assertThatThrownBy(() -> defaults.withRefreshMinutes("jellyfin", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Refresh every 1 to 1440 minutes");
        assertThatThrownBy(() -> defaults.withRefreshMinutes("jellyfin", 1441))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Refresh every 1 to 1440 minutes");
        assertThat(defaults.withRefreshMinutes("jellyfin", 10).withRefreshMinutes("jellyfin", null).refreshMinutes())
                .isEmpty();

        assertThatThrownBy(() -> defaults.withLocale("english", "DE", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use a language tag such as de-DE");
        assertThatThrownBy(() -> defaults.withLocale("de-DE", "Germany", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Use a two-letter country code such as DE");
        var unknownProviders = List.of("hulu");
        assertThatThrownBy(() -> defaults.withLocale("de-DE", "DE", unknownProviders))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown streaming service hulu");
    }

    @Test
    void dropsDuplicatesInOrderAndProviders() {
        SourcePreferences prefs = new SourcePreferences(
                List.of("jellyfin/next-up", "jellyfin/resume", "jellyfin/next-up"), Set.of(), Set.of(), null,
                "de-DE", "DE", List.of("netflix", "dazn", "netflix"));

        assertThat(prefs.railOrder()).containsExactly("jellyfin/next-up", "jellyfin/resume");
        assertThat(prefs.providers()).containsExactly("netflix", "dazn");
    }

    @Test
    void rejectsMalformedRailKeys() {
        var defaults = SourcePreferences.defaults("de-DE", "DE");
        var sourceOnly = List.of("jellyfin");
        assertThatThrownBy(() -> defaults.withRailOrder(sourceOnly))
                .isInstanceOf(IllegalArgumentException.class);
        var pathTraversal = List.of("../x");
        assertThatThrownBy(() -> defaults.withRailOrder(pathTraversal))
                .isInstanceOf(IllegalArgumentException.class);
        var tooManySegments = List.of("a/b/c");
        assertThatThrownBy(() -> defaults.withRailOrder(tooManySegments))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withersReturnCopies() {
        SourcePreferences prefs = SourcePreferences.defaults("de-DE", "DE")
                .withRailOrder(List.of("jellyfin/resume"))
                .withRailVisible("jellyfin/resume", false)
                .withSourceEnabled("jellyfin", false)
                .withRefreshMinutes("jellyfin", 10);

        var railOrder = prefs.railOrder();
        assertThatThrownBy(() -> railOrder.add("x")).isInstanceOf(UnsupportedOperationException.class);
        var hiddenRails = prefs.hiddenRails();
        assertThatThrownBy(() -> hiddenRails.add("x")).isInstanceOf(UnsupportedOperationException.class);
        var disabledSources = prefs.disabledSources();
        assertThatThrownBy(() -> disabledSources.add("x")).isInstanceOf(UnsupportedOperationException.class);
        var refreshMinutes = prefs.refreshMinutes();
        assertThatThrownBy(() -> refreshMinutes.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        var providers = prefs.providers();
        assertThatThrownBy(() -> providers.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
