package dev.andre.homecontrol.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** {@code home-control.events.*}: how often an idle event stream gets a comment line, so proxies keep it open. */
@ConfigurationProperties("home-control.events")
public record EventStreamProperties(@DefaultValue("25s") Duration heartbeatInterval) {
}
