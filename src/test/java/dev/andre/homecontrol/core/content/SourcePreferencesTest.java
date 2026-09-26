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
        var preparedArg45_2 = List.of("hulu");
        assertThatThrownBy(() -> defaults.withLocale("de-DE", "DE", preparedArg45_2))
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
        var preparedReceiver62 = SourcePreferences.defaults("de-DE", "DE");
        var preparedArg62_0 = List.of("jellyfin");
        assertThatThrownBy(() -> preparedReceiver62.withRailOrder(preparedArg62_0))
                .isInstanceOf(IllegalArgumentException.class);
        var preparedReceiver64 = SourcePreferences.defaults("de-DE", "DE");
        var preparedArg64_0 = List.of("../x");
        assertThatThrownBy(() -> preparedReceiver64.withRailOrder(preparedArg64_0))
                .isInstanceOf(IllegalArgumentException.class);
        var preparedReceiver66 = SourcePreferences.defaults("de-DE", "DE");
        var preparedArg66_0 = List.of("a/b/c");
        assertThatThrownBy(() -> preparedReceiver66.withRailOrder(preparedArg66_0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withersReturnCopies() {
        SourcePreferences prefs = SourcePreferences.defaults("de-DE", "DE")
                .withRailOrder(List.of("jellyfin/resume"))
                .withRailVisible("jellyfin/resume", false)
                .withSourceEnabled("jellyfin", false)
                .withRefreshMinutes("jellyfin", 10);

        var preparedReceiver78 = prefs.railOrder();
        assertThatThrownBy(() -> preparedReceiver78.add("x")).isInstanceOf(UnsupportedOperationException.class);
        var preparedReceiver79 = prefs.hiddenRails();
        assertThatThrownBy(() -> preparedReceiver79.add("x")).isInstanceOf(UnsupportedOperationException.class);
        var preparedReceiver80 = prefs.disabledSources();
        assertThatThrownBy(() -> preparedReceiver80.add("x")).isInstanceOf(UnsupportedOperationException.class);
        var preparedReceiver81 = prefs.refreshMinutes();
        assertThatThrownBy(() -> preparedReceiver81.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        var preparedReceiver82 = prefs.providers();
        assertThatThrownBy(() -> preparedReceiver82.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
