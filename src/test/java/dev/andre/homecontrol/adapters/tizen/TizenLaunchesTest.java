package dev.andre.homecontrol.adapters.tizen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TizenLaunchesTest {

    private static Optional<List<TizenApp>> installed() throws IOException {
        return Optional.of(TizenMessages.installedApps(TizenMessages.JSON.readTree(
                Files.readString(Path.of("src/test/resources/fixtures/tizen/installed-apps.json")))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.youtube.com/watch?v=aqz-KE-bpKQ", "https://youtu.be/aqz-KE-bpKQ"})
    void aYouTubeVideoGoesThroughDial(String url) throws IOException {
        TizenLaunch.Dial expected = new TizenLaunch.Dial("YouTube", "v=aqz-KE-bpKQ");

        assertThat(TizenLaunches.forUri(URI.create(url), installed())).isEqualTo(expected);
        assertThat(TizenLaunches.forUri(URI.create(url), Optional.empty())).isEqualTo(expected);
    }

    @Test
    void youTubeWithoutAVideoOpensTheInstalledApp() throws IOException {
        assertThat(TizenLaunches.forUri(URI.create("https://www.youtube.com/"), installed()))
                .isEqualTo(new TizenLaunch.App("111299001912", "YouTube", "DEEP_LINK"));
    }

    @Test
    void netflixOpensTheAppButNeverTheTitle() throws IOException {
        TizenLaunch launch = TizenLaunches.forUri(URI.create("https://www.netflix.com/title/80057281"), installed());

        assertThat(launch).isEqualTo(new TizenLaunch.App("3201907018807", "Netflix", "DEEP_LINK"));
        assertThat(launch.toString()).doesNotContain("80057281");
    }

    @Test
    void anAppThatIsNotInstalledIsUnsupported() throws IOException {
        assertThat(TizenLaunches.forUri(URI.create("https://app.primevideo.com/detail?gti=x"), installed()))
                .isEqualTo(new TizenLaunch.Unsupported("Prime Video is not installed on this TV"));
    }

    @Test
    void withoutAnInstalledListTheWellKnownIdsAreUsed() {
        assertThat(TizenLaunches.forUri(URI.create("https://www.netflix.com/browse"), Optional.empty()))
                .isEqualTo(new TizenLaunch.App("3201907018807", "Netflix", "DEEP_LINK"));
        assertThat(TizenLaunches.forUri(URI.create("https://app.primevideo.com/detail?gti=x"), Optional.empty()))
                .isEqualTo(new TizenLaunch.App("3201910019365", "Prime Video", "DEEP_LINK"));
        assertThat(TizenLaunches.forUri(URI.create("https://www.youtube.com/"), Optional.empty()))
                .isEqualTo(new TizenLaunch.App("111299001912", "YouTube", "DEEP_LINK"));
    }

    @Test
    void nativeAppsLaunchNatively() {
        assertThat(TizenLaunches.forUri(URI.create("https://www.netflix.com/browse"),
                Optional.of(List.of(new TizenApp("x", "Netflix", 4)))))
                .isEqualTo(new TizenLaunch.App("x", "Netflix", "NATIVE_LAUNCH"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/a", "https://www.dazn.com/"})
    void webLinksAreUnsupported(String url) {
        assertThat(TizenLaunches.forUri(URI.create(url), Optional.empty()))
                .isInstanceOfSatisfying(TizenLaunch.Unsupported.class,
                        unsupported -> assertThat(unsupported.reason()).contains("cannot open web links"));
    }

    @Test
    void knownAppsAreTheThreeServicesThatAreInstalled() throws IOException {
        assertThat(TizenLaunches.knownApps(installed())).containsExactly(
                new TizenLaunch.App("111299001912", "YouTube", "DEEP_LINK"),
                new TizenLaunch.App("3201907018807", "Netflix", "DEEP_LINK"));
        assertThat(TizenLaunches.knownApps(Optional.empty())).extracting(TizenLaunch.App::appId)
                .containsExactly("111299001912", "3201907018807", "3201910019365");
    }
}
