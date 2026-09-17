package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.storage.SecretStore;

/** Which TheSportsDB key is in effect: the documented free key, or the household's own, as a secret. */
public class TheSportsDbKeys {

    public static final String SECRET = "sports.thesportsdb.key";

    private final SportsSettingsService settings;
    private final SecretStore secrets;
    private final SportsProperties properties;

    public TheSportsDbKeys(SportsSettingsService settings, SecretStore secrets, SportsProperties properties) {
        this.settings = settings;
        this.secrets = secrets;
        this.properties = properties;
    }

    public String current() {
        if (settings.current().keyKind() == SportsSettings.KeyKind.FREE) {
            return properties.theSportsDb().freeKey();
        }
        return secrets.secret(SECRET).orElseThrow(() -> new TheSportsDbException(
                TheSportsDbException.Kind.UNAUTHORIZED, "Your TheSportsDB key is missing; enter it again or use the free key"));
    }
}
