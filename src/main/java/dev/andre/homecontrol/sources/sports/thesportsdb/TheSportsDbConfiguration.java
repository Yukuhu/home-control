package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The TheSportsDB module. Either {@code home-control.sports.enabled=false} or
 * {@code home-control.sports.thesportsdb.enabled=false} removes all of it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"home-control.sports.enabled", "home-control.sports.thesportsdb.enabled"},
        havingValue = "true", matchIfMissing = true)
public class TheSportsDbConfiguration {

    @Bean
    public TheSportsDbClient theSportsDbClient(SportsProperties properties) {
        return new TheSportsDbClient(properties.theSportsDb());
    }

    @Bean
    public TheSportsDbKeys theSportsDbKeys(SportsSettingsService settings, SecretStore secretStore, SportsProperties properties) {
        return new TheSportsDbKeys(settings, secretStore, properties);
    }

    @Bean
    public TheSportsDbSchedule theSportsDbSchedule(TheSportsDbClient client, TheSportsDbKeys keys,
                                                   SportsSettingsService settings, SportsProperties properties,
                                                   SportsTimeZones zones) {
        return new TheSportsDbSchedule(client, keys, settings, properties, zones, Clock.systemUTC());
    }

    @Bean
    public SportsCompetitions sportsCompetitions(SportsSettingsService settings, TheSportsDbClient client,
                                                 TheSportsDbKeys keys, TheSportsDbSchedule schedule,
                                                 LoginService loginService, SportsProperties properties) {
        return new SportsCompetitions(settings, client, keys, schedule, loginService, properties, Clock.systemUTC());
    }
}
