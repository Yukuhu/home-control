package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Adds/removes TheSportsDB competitions and switches between the free and a personal API key. */
public class SportsCompetitions {

    private static final Pattern LEAGUE_ID = Pattern.compile("^[0-9]{1,9}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9]{1,64}$");
    private static final int MAX_FIELD = 60;

    public record PersonalKey(String key, String loginPassword, String loginPasswordConfirmation) {
        @Override
        public String toString() {
            return "PersonalKey[redacted]";
        }
    }

    private final SportsSettingsService settingsService;
    private final TheSportsDbClient client;
    private final TheSportsDbKeys keys;
    private final TheSportsDbSchedule schedule;
    private final LoginService login;
    private final SportsProperties properties;
    private final Clock clock;

    public SportsCompetitions(SportsSettingsService settingsService, TheSportsDbClient client, TheSportsDbKeys keys,
                              TheSportsDbSchedule schedule, LoginService login, SportsProperties properties, Clock clock) {
        this.settingsService = settingsService;
        this.client = client;
        this.keys = keys;
        this.schedule = schedule;
        this.login = login;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized SportsSettings.CompetitionEntry add(String leagueId) {
        String id = leagueId == null ? "" : leagueId.strip();
        if (!LEAGUE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Enter the competition's TheSportsDB id (digits only)");
        }
        SportsSettings settings = settingsService.current();
        if (settings.competition(id).isPresent()) {
            throw new IllegalArgumentException("That competition is already added");
        }
        if (settings.competitions().size() >= properties.maxCompetitions()) {
            throw new IllegalArgumentException("You can add up to " + properties.maxCompetitions() + " competitions");
        }
        League league = client.lookupLeague(keys.current(), id)
                .orElseThrow(() -> new IllegalArgumentException("TheSportsDB has no competition " + id));
        SportsSettings.CompetitionEntry entry = new SportsSettings.CompetitionEntry(
                id, league.name(), league.sport(), league.country(), league.badge(), null, clock.instant());
        settingsService.update(s -> s.withCompetitions(append(s.competitions(), entry)));
        return entry;
    }

    public synchronized SportsSettings.CompetitionEntry remove(String leagueId) {
        SportsSettings.CompetitionEntry entry = settingsService.current().competition(leagueId)
                .orElseThrow(() -> new IllegalArgumentException("No competition " + leagueId));
        settingsService.update(s -> s.withCompetitions(without(s.competitions(), leagueId)));
        schedule.forget(leagueId);
        return entry;
    }

    public List<League> search(String country, String sport) {
        String strippedCountry = country == null ? "" : country.strip();
        if (strippedCountry.isEmpty()) {
            throw new IllegalArgumentException("Enter a country such as Germany");
        }
        if (strippedCountry.length() > MAX_FIELD) {
            throw new IllegalArgumentException("Keep the country under 60 characters");
        }
        String strippedSport = sport == null ? "" : sport.strip();
        if (strippedSport.length() > MAX_FIELD) {
            throw new IllegalArgumentException("Keep the sport under 60 characters");
        }
        return client.searchLeagues(keys.current(), strippedCountry, strippedSport);
    }

    public synchronized void usePersonalKey(PersonalKey request, HttpServletRequest http) {
        String key = request.key() == null ? "" : request.key().strip();
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("That does not look like a TheSportsDB API key");
        }
        if (!login.loginRequired()) {
            login.checkNewPassword(request.loginPassword(), request.loginPasswordConfirmation());
        }
        try {
            client.lookupLeague(key, "4328");
        } catch (TheSportsDbException e) {
            if (e.kind() == TheSportsDbException.Kind.UNAUTHORIZED) {
                throw new IllegalArgumentException("TheSportsDB rejected that key");
            }
            throw e;
        }
        login.storeSecrets(Map.of(TheSportsDbKeys.SECRET, key), request.loginPassword(),
                request.loginPasswordConfirmation(), http);
        settingsService.update(s -> s.withKeyKind(SportsSettings.KeyKind.PERSONAL));
        schedule.clear();
    }

    public synchronized void useFreeKey() {
        if (settingsService.current().keyKind() == SportsSettings.KeyKind.PERSONAL) {
            login.removeSecrets(List.of(TheSportsDbKeys.SECRET));
        }
        settingsService.update(s -> s.withKeyKind(SportsSettings.KeyKind.FREE));
        schedule.clear();
    }

    private static List<SportsSettings.CompetitionEntry> append(List<SportsSettings.CompetitionEntry> competitions,
                                                                 SportsSettings.CompetitionEntry entry) {
        List<SportsSettings.CompetitionEntry> next = new ArrayList<>(competitions);
        next.add(entry);
        return next;
    }

    private static List<SportsSettings.CompetitionEntry> without(List<SportsSettings.CompetitionEntry> competitions, String leagueId) {
        List<SportsSettings.CompetitionEntry> next = new ArrayList<>(competitions);
        next.removeIf(c -> c.leagueId().equals(leagueId));
        return next;
    }
}
