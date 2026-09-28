package dev.andre.homecontrol.adapters.androidtv;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/** {@code home-control.androidtv.*}: the keystore password and the connection's waits. */
@ConfigurationProperties("home-control.androidtv")
public record AndroidTvProperties(@DefaultValue("true") boolean enabled,
                                  @DefaultValue("shield") String keystorePassword,
                                  @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) Duration staleTimeout,
                                  @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) Duration reconnectInitialDelay,
                                  @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) Duration reconnectMaxDelay) {
}
