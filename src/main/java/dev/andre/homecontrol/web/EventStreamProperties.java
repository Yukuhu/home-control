package dev.andre.homecontrol.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.events.*}: how often an idle event stream gets a comment line, so proxies keep it open. At least
 * a second; the heartbeat cannot be switched off.
 */
@ConfigurationProperties("home-control.events")
@Validated
public record EventStreamProperties(@DefaultValue("25s") @DurationMin(seconds = 1) Duration heartbeatInterval) {
}
