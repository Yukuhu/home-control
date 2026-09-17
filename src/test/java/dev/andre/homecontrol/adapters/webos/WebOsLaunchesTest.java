package dev.andre.homecontrol.adapters.webos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsLaunchesTest {

    private static WebOsLaunch launch(String url) {
        return WebOsLaunches.forUri(URI.create(url));
    }

    private static String payload(WebOsLaunch launch) {
        return SsapMessages.JSON.writeValueAsString(launch.payload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.youtube.com/watch?v=aqz-KE-bpKQ", "https://youtu.be/aqz-KE-bpKQ"})
    void aYouTubeVideoLaunchesTheAppWithContentTarget(String url) {
        WebOsLaunch launch = launch(url);

        assertThat(launch.ssapUri()).isEqualTo("ssap://system.launcher/launch");
        assertThat(payload(launch)).isEqualTo("{\"id\":\"youtube.leanback.v4\","
                + "\"contentId\":\"https://www.youtube.com/tv?v=aqz-KE-bpKQ\","
                + "\"params\":{\"contentTarget\":\"https://www.youtube.com/tv?v=aqz-KE-bpKQ\"}}");
    }

    @Test
    void youTubeWithoutAVideoJustOpensTheApp() {
        WebOsLaunch launch = launch("https://www.youtube.com/");

        assertThat(launch.ssapUri()).isEqualTo("ssap://system.launcher/launch");
        assertThat(payload(launch)).isEqualTo("{\"id\":\"youtube.leanback.v4\"}");
    }

    @Test
    void aNetflixTitleUsesTheConnectSdkContentId() {
        WebOsLaunch launch = launch("https://www.netflix.com/title/80057281");

        assertThat(launch.ssapUri()).isEqualTo("ssap://system.launcher/launch");
        assertThat(payload(launch)).isEqualTo("{\"id\":\"netflix\","
                + "\"contentId\":\"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4\","
                + "\"params\":{\"contentId\":\"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4\"}}");
    }

    @Test
    void netflixWithoutATitleJustOpensTheApp() {
        assertThat(payload(launch("https://www.netflix.com/browse"))).isEqualTo("{\"id\":\"netflix\"}");
    }

    @Test
    void primeVideoOpensTheAmazonApp() {
        WebOsLaunch launch = launch("https://app.primevideo.com/detail?gti=amzn1.dv.gti.1");

        assertThat(launch.ssapUri()).isEqualTo("ssap://system.launcher/launch");
        assertThat(payload(launch)).isEqualTo("{\"id\":\"amazon\"}");
    }

    @Test
    void anythingElseOpensInTheTvBrowser() {
        WebOsLaunch dazn = launch("https://www.dazn.com/de-DE/home");
        WebOsLaunch other = launch("https://example.org/a?b=c");

        assertThat(dazn.ssapUri()).isEqualTo("ssap://system.launcher/open");
        assertThat(payload(dazn)).isEqualTo("{\"target\":\"https://www.dazn.com/de-DE/home\"}");
        assertThat(other.ssapUri()).isEqualTo("ssap://system.launcher/open");
        assertThat(payload(other)).isEqualTo("{\"target\":\"https://example.org/a?b=c\"}");
    }
}
