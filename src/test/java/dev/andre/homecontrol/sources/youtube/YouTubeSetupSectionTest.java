package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** What the setup page shows about YouTube, from the beans the YouTube module has (or lacks). */
class YouTubeSetupSectionTest {

    private static final URI HOME = URI.create("https://home.example.com");
    private static final String EVENING = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG";
    private static final String KIDS = "PLx0sYbCqOb8Q_CLZC2BdBSKEEB59BOPUM";
    private static final String OLD = "PLold00000000000000000000000000000";

    @TempDir
    Path tempDir;

    private final YouTubeSetupService setup = mock(YouTubeSetupService.class);
    private final LoginService login = mock(LoginService.class);
    private final YouTubePlaylists playlists = mock(YouTubePlaylists.class);
    private final DeviceQueries devices = mock(DeviceQueries.class);
    private QuotaLedger ledger;

    @BeforeEach
    void connected() {
        ledger = new QuotaLedger(tempDir.resolve("quota.json"), MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")),
                10000, 20);
        given(setup.hasClient()).willReturn(true);
        given(setup.connected()).willReturn(true);
        given(setup.authorizationStatus()).willReturn(YouTubeAuthorizationService.Status.of(
                YouTubeAuthorizationService.State.CONNECTED, "YouTube connected"));
        given(setup.settings()).willReturn(YouTubeSettings.EMPTY);
        given(playlists.loadedList()).willReturn(List.of());
        given(devices.devices()).willReturn(List.of());
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(bean);
        return provider;
    }

    private YouTubeSetupSection section() {
        return new YouTubeSetupSection(provider(setup), provider(login), provider(ledger), provider(playlists),
                provider(devices));
    }

    @Test
    void withoutTheYouTubeBeansTheSectionOffersTheConnectFormOnly() {
        var bare = new YouTubeSetupSection(provider(null), provider(null), provider(null), provider(null), provider(null));

        YouTubeSetupSection.View view = bare.view(HOME);

        assertThat(view.hasClient()).isFalse();
        assertThat(view.connected()).isFalse();
        assertThat(view.authorization().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
        assertThat(view.needsLoginPassword()).isTrue();
        assertThat(view.quota().dailyUnits()).isZero();
        assertThat(view.playlists()).isEmpty();
        assertThat(view.loungeDevices()).isEmpty();
        assertThat(view.callbackUrl()).isEqualTo("https://home.example.com/setup/sources/youtube/callback");
        assertThat(view.browserSupported()).isTrue();
    }

    @Test
    void aLanAddressCannotUseTheBrowserSignIn() {
        YouTubeSetupSection.View view = section().view(URI.create("http://192.168.1.10:8080"));

        assertThat(view.callbackUrl()).isEqualTo("http://192.168.1.10:8080/setup/sources/youtube/callback");
        assertThat(view.browserSupported()).isFalse();
    }

    @Test
    void aSetLoginPasswordIsNotAskedForAgain() {
        given(login.loginRequired()).willReturn(true);

        assertThat(section().view(HOME).needsLoginPassword()).isFalse();
    }

    @Test
    void loadedPlaylistsComeFirstAndRememberedOnesThatWereNotLoadedFollow() {
        Map<String, String> chosen = new LinkedHashMap<>();
        chosen.put(EVENING, "Watch this evening");
        chosen.put(OLD, "Old list");
        given(setup.settings()).willReturn(new YouTubeSettings(Instant.parse("2026-09-16T09:00:00Z"), "chan", "Andre",
                true, chosen, Set.of(), null));
        given(playlists.loadedList()).willReturn(List.of(
                new YouTubePlaylists.PlaylistSummary(KIDS, "Kids science", 17),
                new YouTubePlaylists.PlaylistSummary(EVENING, "Watch this evening", 2)));

        YouTubeSetupSection.View view = section().view(HOME);

        assertThat(view.channelTitle()).isEqualTo("Andre");
        assertThat(view.watchLater()).isTrue();
        assertThat(view.playlists()).containsExactly(
                new YouTubeSetupSection.PlaylistOption(KIDS, "Kids science", 17, false),
                new YouTubeSetupSection.PlaylistOption(EVENING, "Watch this evening", 2, true),
                new YouTubeSetupSection.PlaylistOption(OLD, "Old list", 0, true));
    }

    @Test
    void onlyCastReceiversCanUseYouTubeCast() {
        Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.7", Map.of(), Instant.EPOCH);
        Device living = new Device("living", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.5", Map.of(), Instant.EPOCH);
        Device office = new Device("office", "Office", DeviceKind.CAST, "10.0.0.8", Map.of(), Instant.EPOCH);
        given(devices.devices()).willReturn(List.of(kitchen, living, office));
        given(devices.capabilities("kitchen")).willReturn(Set.of(Capability.CAST_RECEIVER));
        given(devices.capabilities("living")).willReturn(Set.of(Capability.REMOTE_KEYS));
        given(devices.capabilities("office")).willReturn(Set.of(Capability.CAST_RECEIVER));
        given(setup.settings()).willReturn(new YouTubeSettings(Instant.parse("2026-09-16T09:00:00Z"), "chan", "Andre",
                false, Map.of(), Set.of("office"), "remote"));

        assertThat(section().view(HOME).loungeDevices()).containsExactly(
                new YouTubeSetupSection.LoungeDeviceView("kitchen", "Kitchen", false),
                new YouTubeSetupSection.LoungeDeviceView("office", "Office", true));
    }

    @Test
    void withoutTheDeviceListNoDeviceIsOffered() {
        var noDevices = new YouTubeSetupSection(provider(setup), provider(login), provider(ledger), provider(playlists),
                provider(null));

        assertThat(noDevices.view(HOME).loungeDevices()).isEmpty();
    }

    @Test
    void theQuotaShowsTodaysCallsWithTheirUnits() {
        ledger.charge(QuotaLedger.Call.SEARCH_LIST);
        ledger.charge(QuotaLedger.Call.VIDEOS_LIST);
        ledger.charge(QuotaLedger.Call.VIDEOS_LIST);

        YouTubeSetupSection.QuotaView quota = section().view(HOME).quota();

        assertThat(quota.units()).isEqualTo(102);
        assertThat(quota.dailyUnits()).isEqualTo(10000);
        assertThat(quota.searches()).isEqualTo(1);
        assertThat(quota.searchesPerDay()).isEqualTo(20);
        assertThat(quota.resets()).isNotBlank();
        assertThat(quota.calls()).containsExactlyInAnyOrder(
                new YouTubeSetupSection.CallCount("search.list", 1, 100),
                new YouTubeSetupSection.CallCount("videos.list", 2, 2));
    }
}
