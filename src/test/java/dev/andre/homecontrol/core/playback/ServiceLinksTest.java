package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceLinksTest {

    @ParameterizedTest
    @CsvSource({
            "youtube, YouTube",
            "netflix, Netflix",
            "primevideo, Prime Video",
            "dazn, DAZN",
            "jellyfin, Jellyfin"
    })
    void namesKnownServices(String service, String name) {
        assertThat(ServiceLinks.displayName(service)).contains(name);
    }

    @Test
    void unknownServicesHaveNoDisplayName() {
        assertThat(ServiceLinks.displayName("web")).isEmpty();
        assertThat(ServiceLinks.displayName("unknown")).isEmpty();
    }

    @Test
    void labelFallsBackToTheHost() {
        assertThat(ServiceLinks.label("web", URI.create("https://example.org/a"))).isEqualTo("example.org");
        assertThat(ServiceLinks.label("netflix", URI.create("https://www.netflix.com/title/1"))).isEqualTo("Netflix");
    }
}
