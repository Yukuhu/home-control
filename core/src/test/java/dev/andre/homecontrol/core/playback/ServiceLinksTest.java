package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @ParameterizedTest
    @CsvSource({
            "https://www.netflix.com/de/title/80057281?s=a&trkid=13747225, https://www.netflix.com/title/80057281",
            "https://www.netflix.com/de-en/title/80057281, https://www.netflix.com/title/80057281",
            "https://www.netflix.com/watch/80057281?trackId=1, https://www.netflix.com/title/80057281",
            "https://netflix.com/title/80057281, https://www.netflix.com/title/80057281",
            "https://www.primevideo.com/region/eu/detail/amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6/ref=atv_dp_share_cu_r, https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6",
            "https://app.primevideo.com/detail?gti=amzn1.dv.gti.8EB3C4A1-1B2C-4D5E-9F60-718293A4B5C6&ref_=x, https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6",
            "https://www.primevideo.com/-/de/detail/0HAQAA7JM43QWX0H6GUD3IOF70/ref=atv_sr, https://www.primevideo.com/detail/0HAQAA7JM43QWX0H6GUD3IOF70",
            "https://www.amazon.de/gp/video/detail/B0B8TJ4WQS/ref=atv_dp?language=de, https://www.amazon.de/gp/video/detail/B0B8TJ4WQS"
    })
    void canonicalLinks(String pasted, String canonical) {
        assertThat(ServiceLinks.canonical(URI.create(pasted))).isEqualTo(URI.create(canonical));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.netflix.com/browse/genre/83",
            "https://app.primevideo.com/detail?gti=amzn1.dv.gti.1",
            "https://www.amazon.de/gp/video/detail/B08XYZ",
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=30",
            "https://youtu.be/aqz-KE-bpKQ",
            "https://www.dazn.com/de-DE/fixture/ContentId:abc",
            "https://example.org/a?b=c#d",
            "https://www.notnetflix.com/title/1"
    })
    void keepsEverythingElseAsPasted(String url) {
        URI uri = URI.create(url);
        assertThat(ServiceLinks.canonical(uri)).isEqualTo(uri);
    }

    @Test
    void buildsTitleLinksFromIds() {
        assertThat(ServiceLinks.netflixTitle("80057281")).isEqualTo(URI.create("https://www.netflix.com/title/80057281"));
        assertThat(ServiceLinks.primeVideoDetail("AMZN1.DV.GTI.8EB3C4A1-1B2C-4D5E-9F60-718293A4B5C6"))
                .isEqualTo(URI.create("https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6"));

        assertThatThrownBy(() -> ServiceLinks.netflixTitle("80057281&x=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceLinks.netflixTitle("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceLinks.primeVideoDetail("amzn1.dv.gti.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsIds() {
        assertThat(ServiceLinks.netflixTitleId(URI.create("https://www.netflix.com/de/title/80057281?s=a"))).contains("80057281");
        assertThat(ServiceLinks.netflixTitleId(URI.create("https://www.netflix.com/watch/80057281?trackId=1"))).contains("80057281");
        assertThat(ServiceLinks.netflixTitleId(URI.create("https://example.org/title/80057281"))).isEmpty();
        assertThat(ServiceLinks.primeVideoGti(URI.create("https://www.amazon.de/gp/video/detail/B0B8TJ4WQS"))).isEmpty();
    }

    @Test
    void appHomes() {
        assertThat(ServiceLinks.appHome("netflix")).contains(URI.create("https://www.netflix.com/browse"));
        assertThat(ServiceLinks.appHome("primevideo")).contains(URI.create("https://app.primevideo.com/"));
        assertThat(ServiceLinks.appHome("dazn")).contains(URI.create("https://www.dazn.com/"));
        assertThat(ServiceLinks.appHome("youtube")).isEmpty();
        assertThat(ServiceLinks.appHome("disneyplus")).isEmpty();
        assertThat(ServiceLinks.appHome("web")).isEmpty();

        assertThat(ServiceLinks.isAppHome(URI.create("https://www.netflix.com/browse"))).isTrue();
        assertThat(ServiceLinks.isAppHome(URI.create("https://app.primevideo.com/"))).isTrue();
        assertThat(ServiceLinks.isAppHome(URI.create("https://www.dazn.com/"))).isTrue();
        assertThat(ServiceLinks.isAppHome(URI.create("https://www.netflix.com/title/80057281"))).isFalse();
        assertThat(ServiceLinks.isAppHome(URI.create("https://www.netflix.com/browse?x=1"))).isFalse();
    }

    @Test
    void appLinkCarriesTheServiceOfTheCanonicalLink() {
        assertThat(ServiceLinks.appLink(URI.create("https://www.netflix.com/de/title/80057281?s=a")))
                .isEqualTo(new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));
        assertThat(ServiceLinks.appLink(URI.create("https://www.amazon.de/gp/video/detail/B0B8TJ4WQS/ref=x")).service())
                .isEqualTo("primevideo");
    }
}
