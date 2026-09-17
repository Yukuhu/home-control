package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTest {

    @Test
    void titleLinksOpenInTheApp() {
        Route route = new Route.OpenAppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix");

        assertThat(route.describe()).isEqualTo("Open in the Netflix app");
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.netflix.com/browse, netflix, Open the Netflix app (not this title)",
            "https://app.primevideo.com/, primevideo, Open the Prime Video app (not this title)",
            "https://www.dazn.com/, dazn, Open the DAZN app (not this title)"
    })
    void appHomeLinksSayTheyDoNotOpenTheTitle(String uri, String service, String description) {
        Route route = new Route.OpenAppLink(URI.create(uri), service);

        assertThat(route.describe()).isEqualTo(description);
    }

    @Test
    void webLinksNameTheHost() {
        Route route = new Route.OpenAppLink(URI.create("https://example.org/a"), "web");

        assertThat(route.describe()).isEqualTo("Open example.org on the device");
    }
}
