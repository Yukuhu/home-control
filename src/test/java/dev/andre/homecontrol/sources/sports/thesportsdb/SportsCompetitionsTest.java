package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.JsonFileSportsStore;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SportsCompetitionsTest {

    @TempDir
    Path dir;

    private FakeTheSportsDbServer server;
    private SportsSettingsService settingsService;
    private LoginService login;
    private TheSportsDbSchedule schedule;
    private SportsCompetitions competitions;
    private HttpServletRequest http;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();

        JsonFileSportsStore store = new JsonFileSportsStore(dir.resolve("sports.json"));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        settingsService = new SportsSettingsService(store, events);

        login = mock(LoginService.class);
        given(login.loginRequired()).willReturn(false);
        http = mock(HttpServletRequest.class);

        SportsProperties properties = new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24), 1, 2, null));

        TheSportsDbClient client = new TheSportsDbClient(properties.theSportsDb());
        TheSportsDbKeys keys = new TheSportsDbKeys(settingsService, mock(dev.andre.homecontrol.storage.SecretStore.class), properties);
        dev.andre.homecontrol.sources.sports.SportsTimeZones zones =
                mock(dev.andre.homecontrol.sources.sports.SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        schedule = new TheSportsDbSchedule(client, keys, settingsService, properties,
                zones, Clock.fixed(Instant.parse("2026-09-19T14:00:00Z"), ZoneOffset.UTC));

        competitions = new SportsCompetitions(settingsService, client, keys, schedule, login, properties,
                Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void addsACompetitionFromLookup() {
        SportsSettings.CompetitionEntry entry = competitions.add(" 4331 ");

        assertThat(entry.leagueId()).isEqualTo("4331");
        assertThat(entry.name()).isEqualTo("German Bundesliga");
        assertThat(entry.sport()).isEqualTo("Soccer");
        assertThat(entry.country()).isEqualTo("Germany");
        assertThat(entry.provider()).isNull();
        assertThat(settingsService.current().competition("4331")).isPresent();
    }

    @Test
    void rejectsBadCompetitions() {
        assertThatThrownBy(() -> competitions.add("43a1"))
                .hasMessage("Enter the competition's TheSportsDB id (digits only)");
        assertThatThrownBy(() -> competitions.add(""))
                .hasMessage("Enter the competition's TheSportsDB id (digits only)");

        competitions.add("4331");
        assertThatThrownBy(() -> competitions.add("4331")).hasMessage("That competition is already added");

        assertThatThrownBy(() -> competitions.add("999")).hasMessage("TheSportsDB has no competition 999");
    }

    @Test
    void maxCompetitionsIsEnforced() {
        SportsProperties limited = new SportsProperties(true, "", 30, 10, 1, Duration.ofMinutes(120),
                new SportsProperties.Calendar(Duration.ofHours(6), 1, 2, 5242880, 3, true),
                new SportsProperties.TheSportsDb(true, server.apiBase(), "123", Duration.ofHours(24), 1, 2, null));
        TheSportsDbClient client = new TheSportsDbClient(limited.theSportsDb());
        TheSportsDbKeys keys = new TheSportsDbKeys(settingsService, mock(dev.andre.homecontrol.storage.SecretStore.class), limited);
        SportsCompetitions limitedCompetitions = new SportsCompetitions(settingsService, client, keys, schedule, login,
                limited, Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC));

        limitedCompetitions.add("4331");
        assertThatThrownBy(() -> limitedCompetitions.add("4328")).hasMessage("You can add up to 1 competitions");
    }

    @Test
    void removes() {
        competitions.add("4331");
        SportsSettings.CompetitionEntry removed = competitions.remove("4331");
        assertThat(removed.leagueId()).isEqualTo("4331");
        assertThat(settingsService.current().competition("4331")).isEmpty();

        assertThatThrownBy(() -> competitions.remove("4331")).hasMessage("No competition 4331");
    }

    @Test
    void searches() {
        assertThat(competitions.search(" Germany ", "Soccer")).hasSize(4);
        assertThatThrownBy(() -> competitions.search("", "Soccer")).hasMessage("Enter a country such as Germany");
        assertThatThrownBy(() -> competitions.search("Germany", "s".repeat(61)))
                .hasMessage("Keep the sport under 60 characters");
    }

    @Test
    void storesAPersonalKeyAfterVerifyingIt() {
        competitions.usePersonalKey(new SportsCompetitions.PersonalKey(" 9876543210 ", "household password", "household password"), http);

        verify(login).checkNewPassword("household password", "household password");
        verify(login).storeSecrets(java.util.Map.of(TheSportsDbKeys.SECRET, "9876543210"),
                "household password", "household password", http);
        assertThat(settingsService.current().keyKind()).isEqualTo(SportsSettings.KeyKind.PERSONAL);
        assertThat(server.last("lookupleague.php").key()).isEqualTo("9876543210");
    }

    @Test
    void rejectsWrongKeys() {
        assertThatThrownBy(() -> competitions.usePersonalKey(
                new SportsCompetitions.PersonalKey("bad key!", "household password", "household password"), http))
                .hasMessage("That does not look like a TheSportsDB API key");
        assertThat(server.count("lookupleague.php")).isZero();

        assertThatThrownBy(() -> competitions.usePersonalKey(
                new SportsCompetitions.PersonalKey("1111", "household password", "household password"), http))
                .hasMessage("TheSportsDB rejected that key");
        assertThat(settingsService.current().keyKind()).isEqualTo(SportsSettings.KeyKind.FREE);
    }

    @Test
    void backToTheFreeKey() {
        competitions.usePersonalKey(new SportsCompetitions.PersonalKey("9876543210", "household password", "household password"), http);

        competitions.useFreeKey();

        verify(login).removeSecrets(List.of(TheSportsDbKeys.SECRET));
        assertThat(settingsService.current().keyKind()).isEqualTo(SportsSettings.KeyKind.FREE);
    }

    @Test
    void personalKeyToStringIsRedacted() {
        SportsCompetitions.PersonalKey key = new SportsCompetitions.PersonalKey("9876543210", "pw", "pw");
        assertThat(key.toString()).isEqualTo("PersonalKey[redacted]");
    }
}
