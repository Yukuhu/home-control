package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.bluetooth.BluetoothHostChecks;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothPairingService;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothProperties;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothScan;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.SearchService;
import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.content.StoredRailPreferences;
import dev.andre.homecontrol.core.CodePairing;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.playback.DeepLinkTestProperties;
import dev.andre.homecontrol.playback.DeepLinkTestService;
import dev.andre.homecontrol.playback.PlaybackService;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupService;
import dev.andre.homecontrol.sources.pinned.PinnedProperties;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.sources.sports.thesportsdb.SportsCompetitions;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupService;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.workflows.WorkflowTestService;
import dev.andre.homecontrol.sources.youtube.YouTubeAuthorizationService;
import dev.andre.homecontrol.sources.youtube.YouTubeHttp;
import dev.andre.homecontrol.sources.youtube.YouTubeProperties;
import dev.andre.homecontrol.sources.youtube.YouTubeSettings;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.web.DeviceStateBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;

import static org.mockito.BDDMockito.given;

/**
 * The one web-layer test context: {@code @WebMvcTest} over every controller and controller advice, with every
 * collaborator they need declared here once. A test class extends it and declares no beans of its own (no
 * {@code @MockitoBean}, {@code @Import} or nested {@code @TestConfiguration}): anything that differs between classes
 * gives them separate contexts, which {@code SharedContextRulesTest} forbids. Mockito resets the mocks after each test;
 * {@link #stubSafeDefaults()} then answers the calls every {@code /setup} render makes, which Mockito would answer
 * with {@code null}.
 */
@WebMvcTest(properties = "home-control.bluetooth.enabled=true")
@Import(WebSliceTest.SliceBeans.class)
public abstract class WebSliceTest {

    @MockitoBean
    protected DeviceQueries devices;
    @MockitoBean
    protected DeviceCommands commands;
    @MockitoBean
    protected DeviceEnrollment enrollment;
    @MockitoBean
    protected DeviceSettings deviceSettings;
    @MockitoBean
    protected CodePairing pairing;
    @MockitoBean
    protected BluetoothPairingService bluetoothPairing;
    @MockitoBean
    protected BluetoothHostChecks bluetoothChecks;
    @MockitoBean
    protected BluetoothProperties bluetoothProperties;
    @MockitoBean
    protected JellyfinClient jellyfinClient;
    @MockitoBean
    protected JellyfinSetupService jellyfinSetup;
    /** Also the slice's {@code PinnedLinks}, as in production. */
    @MockitoBean
    protected PinnedShortcuts pins;
    @MockitoBean
    protected SportsCalendars sportsCalendars;
    @MockitoBean
    protected SportsSettingsService sportsSettings;
    @MockitoBean
    protected SportsTimeZones sportsZones;
    @MockitoBean
    protected CalendarSchedule calendarSchedule;
    @MockitoBean
    protected SportsCompetitions sportsCompetitions;
    @MockitoBean
    protected TheSportsDbSchedule theSportsDbSchedule;
    @MockitoBean
    protected TmdbSetupService tmdbSetup;
    @MockitoBean
    protected WorkflowStore workflowStore;
    @MockitoBean
    protected WorkflowTestService workflowTests;
    @MockitoBean
    protected LoginService login;
    @MockitoBean
    protected LoginRateLimiter loginRateLimiter;
    @MockitoBean
    protected YouTubeSetupService youTubeSetup;
    @MockitoBean
    protected YouTubeHttp youTubeHttp;
    @MockitoBean
    protected ContentSources sources;
    @MockitoBean
    protected RailCache rails;
    @MockitoBean
    protected SearchService search;
    @MockitoBean
    protected PlaybackService playback;
    @MockitoBean
    protected DeepLinkTestService deepLinkTests;
    @MockitoBean
    protected DeviceStateBroadcaster broadcaster;
    @MockitoBean
    protected SourcePreferencesService sourcePreferences;
    @MockitoBean
    protected StoredRailPreferences railPreferences;

    @Autowired
    protected StubPromptPairing promptPairing;

    /** Runs before each subclass's own {@code @BeforeEach}, whose stubs then win. */
    @BeforeEach
    protected void stubSafeDefaults() {
        given(bluetoothPairing.lastScan()).willReturn(BluetoothScan.NONE);
        given(sportsSettings.current()).willReturn(SportsSettings.empty());
        given(sportsZones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        given(sportsZones.chosen()).willReturn(true);
        given(sourcePreferences.current()).willReturn(SourcePreferences.defaults("de-DE", "DE"));
        given(youTubeSetup.settings()).willReturn(YouTubeSettings.EMPTY);
        given(youTubeSetup.authorizationStatus()).willReturn(
                new YouTubeAuthorizationService.Status(YouTubeAuthorizationService.State.IDLE, null, null, null, null));
        promptPairing.reset();
    }

    /**
     * The section with this element id on a page the slice rendered. Every {@code /setup} render carries every
     * module's section, so a check on the setup page reads its own section: a word or a form field that another
     * section also renders would otherwise pass for the wrong reason.
     */
    protected static String section(String page, String id) {
        int start = page.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            throw new AssertionError("No element with id \"" + id + "\" on the page");
        }
        int end = page.indexOf("</section>", start);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SliceBeans {

        @Bean
        PinnedProperties pinnedProperties() {
            return new PinnedProperties(true, 200);
        }

        @Bean
        SportsProperties sportsProperties() {
            return new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                    new SportsProperties.Calendar(Duration.ofHours(6), Duration.ofSeconds(5), Duration.ofSeconds(15),
                    5242880, 3, false),
                    new SportsProperties.TheSportsDb(true, URI.create("https://www.thesportsdb.com/api/v1/json"),
                            "123", Duration.ofHours(24), Duration.ofSeconds(5), Duration.ofSeconds(15), null));
        }

        @Bean
        YouTubeProperties youTubeProperties() {
            return new YouTubeProperties(true, URI.create("http://oauth.test"), URI.create("http://api.test"),
                    URI.create("http://lounge.test"), URI.create("http://thumbs.test"), Duration.ofSeconds(2),
                    Duration.ofSeconds(5), 10000, 20, 30, 30, 5,
                    Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6));
        }

        @Bean
        DeepLinkTestProperties deepLinkTestProperties() {
            return new DeepLinkTestProperties(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"),
                    Duration.ofSeconds(10));
        }

        @Bean
        StubPromptPairing promptPairing() {
            return new StubPromptPairing();
        }
    }
}
