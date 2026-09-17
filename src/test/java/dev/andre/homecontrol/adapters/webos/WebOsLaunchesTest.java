package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.playback.ServiceLinks;
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

    @Test
    void serviceLinkBuildersLaunchPerPlatform() {
        String netflixPayload = "{\"id\":\"netflix\","
                + "\"contentId\":\"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4\","
                + "\"params\":{\"contentId\":\"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4\"}}";

        WebOsLaunch fromTitle = WebOsLaunches.forUri(ServiceLinks.netflixTitle("80057281"));
        assertThat(fromTitle.ssapUri()).isEqualTo("ssap://system.launcher/launch");
        assertThat(payload(fromTitle)).isEqualTo(netflixPayload);

        WebOsLaunch fromWatch = WebOsLaunches.forUri(
                ServiceLinks.canonical(URI.create("https://www.netflix.com/de/watch/80057281?trackId=1")));
        assertThat(payload(fromWatch)).isEqualTo(netflixPayload);

        WebOsLaunch netflixHome = WebOsLaunches.forUri(ServiceLinks.appHome("netflix").orElseThrow());
        assertThat(payload(netflixHome)).isEqualTo("{\"id\":\"netflix\"}");

        WebOsLaunch fromGti = WebOsLaunches.forUri(
                ServiceLinks.primeVideoDetail("amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6"));
        assertThat(payload(fromGti)).isEqualTo("{\"id\":\"amazon\"}");

        WebOsLaunch fromAsin = WebOsLaunches.forUri(URI.create("https://www.amazon.de/gp/video/detail/B0B8TJ4WQS"));
        assertThat(payload(fromAsin)).isEqualTo("{\"id\":\"amazon\"}");

        WebOsLaunch primeHome = WebOsLaunches.forUri(ServiceLinks.appHome("primevideo").orElseThrow());
        assertThat(payload(primeHome)).isEqualTo("{\"id\":\"amazon\"}");
    }
}
