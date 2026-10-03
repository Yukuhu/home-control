package dev.andre.homecontrol.content;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A locale or region the rails could not use fails startup and names its setting, not the dashboard later. */
class ContentPropertiesTest {

    private static final ContentProperties.Rails RAILS =
            new ContentProperties.Rails(true, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of());
    private static final ContentProperties.Search SEARCH = new ContentProperties.Search(Duration.ofSeconds(8));

    @Test
    void aCanonicalLocaleAndATwoLetterRegionAreAccepted() {
        ContentProperties properties = new ContentProperties(RAILS, SEARCH, "en-US", "US");

        assertThat(properties.locale()).isEqualTo("en-US");
        assertThat(properties.region()).isEqualTo("US");
    }

    @Test
    void aLocaleTheRailsCannotUseNamesItsSetting() {
        for (String locale : new String[] {"en-us", "en_US", "english"}) {
            assertThatThrownBy(() -> new ContentProperties(RAILS, SEARCH, locale, "DE"))
                    .as(locale).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("home-control.content.locale (HOME_CONTROL_LOCALE)")
                    .hasMessageContaining(locale);
        }
    }

    @Test
    void aRegionTheRailsCannotUseNamesItsSetting() {
        assertThatThrownBy(() -> new ContentProperties(RAILS, SEARCH, "de-DE", "us"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.content.region (HOME_CONTROL_REGION)")
                .hasMessageContaining("us");
    }
}
