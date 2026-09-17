package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetcher;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.calendar.CalendarUrlPolicy;
import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

/** The sports module. {@code home-control.sports.enabled=false} removes all of it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "home-control.sports.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SportsProperties.class)
public class SportsConfiguration {

    @Bean
    public JsonFileSportsStore jsonFileSportsStore(AndroidTvProperties androidTvProperties) {
        return new JsonFileSportsStore(androidTvProperties.dataDir().resolve("sports.json"));
    }

    @Bean
    public SportsSettingsService sportsSettingsService(JsonFileSportsStore store, ApplicationEventPublisher events) {
        return new SportsSettingsService(store, events);
    }

    @Bean
    public SportsTimeZones sportsTimeZones(SportsSettingsService settings, SportsProperties properties) {
        return new SportsTimeZones(settings, properties);
    }

    @Bean
    public CalendarUrlPolicy calendarUrlPolicy(SportsProperties properties) {
        return new CalendarUrlPolicy(properties.calendar().allowLoopback());
    }

    @Bean
    public CalendarFetcher calendarFetcher(SportsProperties properties, CalendarUrlPolicy policy) {
        return new CalendarFetcher(properties.calendar(), policy);
    }

    @Bean
    public CalendarSchedule calendarSchedule(SportsSettingsService settings, CalendarFetcher fetcher,
                                             SecretStore secretStore, SportsProperties properties,
                                             SportsTimeZones zones) {
        return new CalendarSchedule(settings, fetcher, secretStore, properties, zones, Clock.systemUTC());
    }

    @Bean
    public SportsCalendars sportsCalendars(SportsSettingsService settings, CalendarUrlPolicy policy,
                                           CalendarFetcher fetcher, CalendarSchedule schedule,
                                           SecretStore secretStore, LoginService loginService,
                                           SportsProperties properties) {
        return new SportsCalendars(settings, policy, fetcher, schedule, secretStore, loginService, properties,
                Clock.systemUTC(), new SecureRandom());
    }

    @Bean
    public SportsSchedule sportsSchedule(CalendarSchedule schedule) {
        return new SportsSchedule(schedule);
    }

    @Bean
    public SportsContentSource sportsContentSource(SportsSettingsService settings, SportsSchedule schedule,
                                                    SportsTimeZones zones,
                                                    SourcePreferencesService sourcePreferencesService) {
        return new SportsContentSource(settings, schedule, zones, sourcePreferencesService::current, Clock.systemUTC());
    }
}
