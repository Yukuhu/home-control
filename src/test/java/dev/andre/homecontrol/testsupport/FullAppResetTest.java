package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakePairingServer;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;
import dev.andre.homecontrol.sources.tmdb.TmdbCredential;
import dev.andre.homecontrol.sources.tmdb.TmdbImages;
import dev.andre.homecontrol.sources.tmdb.TmdbMediaRef;
import dev.andre.homecontrol.sources.tmdb.TmdbWatchProviders;
import dev.andre.homecontrol.sources.workflows.WorkflowFixtures;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.FakeGoogleServer;
import dev.andre.homecontrol.sources.youtube.KnownVideos;
import dev.andre.homecontrol.sources.youtube.QuotaLedger;
import dev.andre.homecontrol.sources.youtube.YouTubeAuthorizationService;
import dev.andre.homecontrol.sources.youtube.YouTubeException;
import dev.andre.homecontrol.sources.youtube.YouTubeSearch;
import dev.andre.homecontrol.sources.youtube.YouTubeSettings;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.sources.youtube.YouTubeVideo;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.device.Devices;
import dev.andre.homecontrol.security.RequestLoginContext;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.themes.ThemeCatalog;
import dev.andre.homecontrol.themes.ThemeDescriptor;
import dev.andre.homecontrol.themes.ThemeTestPackages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** One reset brings the shared application back to a fresh install, whatever a test class left behind. */
class FullAppResetTest extends FullAppTest {

    private static final String PASSWORD = "reset-test-password";

    @Autowired
    ApplicationContext context;

    @Autowired
    DeviceQueries devices;

    @Autowired
    DeviceEnrollment enrollment;

    @Autowired
    LoginService login;

    @Autowired
    SecretStore secrets;

    @Autowired
    LoginRateLimiter limiter;

    @Autowired
    PinnedShortcuts pins;

    @Autowired
    SportsSettingsService sports;

    @Autowired
    QuotaLedger quota;

    @Autowired
    KnownVideos knownVideos;

    @Test
    void resetBringsTheApplicationBackToAFreshInstall() throws Exception {
        enrollment.adopt(new Device("reset-probe", "Probe", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of()), Instant.now()));
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new RequestLoginContext(new MockHttpServletRequest(), login));
        for (int i = 0; i < 5; i++) {
            limiter.failed("127.0.0.1");
        }
        pins.add("https://www.netflix.com/title/1", "Stranger Things");
        sports.update(current -> current.withTimeZone("Europe/Berlin"));
        context.getBean(JsonFileSourceSettings.class).putPreferences(SourcePreferences.defaults("de-DE", "DE"));
        quota.charge(QuotaLedger.Call.VIDEOS_LIST);
        knownVideos.remember(List.of(new YouTubeVideo("Kz1aT5nM3pQ", "A", "Chan", Instant.now())));
        try (HttpClient http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(SharedFakes.tmdb().url().resolve("/3/probe")).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        ThemeCatalog themes = context.getBean(ThemeCatalog.class);
        themes.install(ThemeTestPackages.zip(ThemeTestPackages.files("reset-probe", null)), null);
        RailCache rails = context.getBean(RailCache.class);
        rails.snapshots();
        assertThat(themes.themes()).anyMatch(theme -> theme.id().equals("reset-probe"));
        assertThat(rails.peek()).isNotEmpty();
        assertThat(login.loginRequired()).isTrue();
        assertThat(limiter.blockedFor("127.0.0.1")).isPresent();
        assertThat(dataDir().resolve("pinned.json")).exists();
        assertThat(SharedFakes.tmdb().requests()).isNotEmpty();

        FullAppReset.reset(context);

        assertThat(devices.devices()).isEmpty();
        assertThat(login.loginRequired()).isFalse();
        assertThat(secrets.accountCredentialNames()).isEmpty();
        assertThat(limiter.blockedFor("127.0.0.1")).isEmpty();
        assertThat(pins.all()).isEmpty();
        assertThat(sports.current()).isEqualTo(SportsSettings.empty());
        assertThat(quota.usage().units()).isZero();
        assertThat(knownVideos.find("Kz1aT5nM3pQ")).isEmpty();
        assertThat(context.getBean(JsonFileSourceSettings.class).preferences()).isEmpty();
        for (String file : new String[]{"sources.json", "youtube-quota.json"}) {
            assertThat(dataDir().resolve(file)).doesNotExist();
        }
        assertThat(SharedFakes.tmdb().requests()).isEmpty();
        assertThat(themes.themes()).allMatch(ThemeDescriptor::builtIn);
        assertThat(rails.peek()).isEmpty();
    }

    @Test
    void resetRemovesTheWorkflowsAndTheYouTubeConnection() {
        RequestLoginContext browser = new RequestLoginContext(new MockHttpServletRequest(), login);
        login.storeSecrets(youTubeAccount(), PASSWORD, PASSWORD, browser);
        WorkflowStore workflows = context.getBean(WorkflowStore.class);
        workflows.create(WorkflowFixtures.single(URI.create("http://127.0.0.1:9/news")), null, null, browser);
        awaitRailFetch("workflows");
        YouTubeSetupService youtube = context.getBean(YouTubeSetupService.class);
        assertThat(youtube.connected()).isTrue();
        assertThat(workflows.all()).hasSize(1);

        FullAppReset.reset(context);

        assertThat(youtube.connected()).isFalse();
        assertThat(workflows.all()).isEmpty();
    }

    @Test
    void resetEndsAYouTubeSignInUnderway() {
        SharedFakes.google().oauthApproves();
        YouTubeSetupService youtube = context.getBean(YouTubeSetupService.class);
        YouTubeSetupService.ConnectRequest signIn = new YouTubeSetupService.ConnectRequest(
                "123456789012-abc123def456.apps.googleusercontent.com", "reset-secret", PASSWORD, PASSWORD);
        youtube.connect(signIn, new RequestLoginContext(new MockHttpServletRequest(), login));
        assertThat(youtube.authorizationStatus().state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);

        FullAppReset.reset(context);

        assertThat(youtube.authorizationStatus().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
    }

    @Test
    void resetForgetsWhatTheSourcesCached() {
        SharedFakes.google().oauthApproves().youtubeLibrary()
                .respond("GET", "/youtube/v3/search", FakeGoogleServer.Canned.fixture(200, "search-videos.json"));
        login.storeSecrets(youTubeAccount(), PASSWORD, PASSWORD,
                new RequestLoginContext(new MockHttpServletRequest(), login));
        YouTubeSearch search = context.getBean(YouTubeSearch.class);
        assertThat(search.search("reset probe", 5)).isNotEmpty();
        TmdbCredential tmdbKey = TmdbCredential.parse("0123456789abcdef0123456789abcdef");
        TmdbMediaRef movie = new TmdbMediaRef(TmdbMediaRef.Type.MOVIE, 603);
        TmdbWatchProviders providers = context.getBean(TmdbWatchProviders.class);
        TmdbImages images = context.getBean(TmdbImages.class);
        SharedFakes.tmdb().withStandardResponses();
        providers.providers(tmdbKey, movie, "DE");
        images.poster(tmdbKey, "/poster.jpg");
        TheSportsDbSchedule fixtures = context.getBean(TheSportsDbSchedule.class);
        String match = followBundesligaWithAMatchNow(fixtures);

        FullAppReset.reset(context);

        assertThatThrownBy(() -> search.search("reset probe", 5)).isInstanceOf(YouTubeException.class);
        SharedFakes.tmdb().withStandardResponses();
        providers.providers(tmdbKey, movie, "DE");
        images.poster(tmdbKey, "/poster.jpg");
        assertThat(SharedFakes.tmdb().requests("GET", "/3/movie/603/watch/providers")).hasSize(1);
        assertThat(SharedFakes.tmdb().requests("GET", "/3/configuration")).hasSize(1);
        assertThat(fixtures.find(match)).isEmpty();
    }

    @Test
    void resetCancelsAPendingPairing() throws Exception {
        PairingService pairing = context.getBean(PairingService.class);
        try (FakePairingServer tv = new FakePairingServer()) {
            pairing.begin("127.0.0.1", tv.port(), "Reset probe");
            assertThat(pairing.inProgress()).isTrue();

            FullAppReset.reset(context);

            assertThat(pairing.inProgress()).isFalse();
        }
    }

    private static Map<String, String> youTubeAccount() {
        return Map.of(YouTubeSettings.CLIENT_ID, "reset-client", YouTubeSettings.CLIENT_SECRET, "reset-secret",
                YouTubeSettings.REFRESH_TOKEN, "reset-refresh-token");
    }

    /** Follows the Bundesliga, answers today's fixtures with one match, and returns that match's item id. */
    private String followBundesligaWithAMatchNow(TheSportsDbSchedule fixtures) {
        Instant kickOff = Instant.now().minus(Duration.ofMinutes(20));
        LocalDate day = LocalDate.ofInstant(kickOff, ZoneOffset.UTC);
        String events = "{\"events\":[{\"idEvent\":\"9000001\",\"idLeague\":\"4331\",\"strLeague\":\"German Bundesliga\","
                + "\"strSport\":\"Soccer\",\"strEvent\":\"Reset Probe Match\",\"strHomeTeam\":\"Home\",\"strAwayTeam\":\"Away\","
                + "\"strTimestamp\":\"" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC).format(kickOff.truncatedTo(ChronoUnit.SECONDS))
                + "\",\"dateEvent\":\"" + day + "\",\"strStatus\":\"NS\",\"strPostponed\":\"no\"}]}";
        SharedFakes.theSportsDb().respondJson("eventsday.php", Map.of("d", day.toString(), "l", "4331"), 200, events);
        sports.update(current -> current.withCompetitions(List.of(new SportsSettings.CompetitionEntry("4331",
                "German Bundesliga", "Soccer", "Germany", null, null, Instant.now()))));
        awaitRailFetch("sports");
        List<SportsEvent> found = fixtures.events().events();
        assertThat(found).isNotEmpty();
        return found.getFirst().itemId();
    }

    /** A rail fetch the test set off ends before the reset, so it cannot leave a request on a fake afterwards. */
    private void awaitRailFetch(String sourceId) {
        RailCache rails = context.getBean(RailCache.class);
        await().until(() -> rails.peek().stream().filter(rail -> rail.sourceId().equals(sourceId))
                .noneMatch(rail -> rail.status() == RailStatus.LOADING || rail.refreshing()));
    }

    @Test
    void theApplicationHoldsOneDevicesAndOneBeanPerDeviceInterface() {
        assertThat(context.getBeansOfType(Devices.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceQueries.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceCommands.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceEnrollment.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceSettings.class)).hasSize(1);
    }

    @Test
    void aReceiverDiscoveryAnnouncesIsMergedIntoTheDeviceAtItsAddress() {
        enrollment.adopt(new Device("listener-tv", "Listener TV", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of()), Instant.EPOCH));
        try {
            context.publishEvent(new DeviceDiscoveredEvent(
                    new DiscoveredDevice("cast", "Listener TV", "127.0.0.1", 9, Map.of("id", "listener-cast"))));

            assertThat(devices.device("listener-tv")).get().satisfies(device ->
                    assertThat(device.hasAdapter("cast")).as("merged by the discovery listener").isTrue());
        } finally {
            enrollment.forget("listener-tv");
        }
    }
}
