package dev.andre.homecontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/** {@code home-control.*}: where the application keeps its files, and whether it looks for devices with mDNS. */
@ConfigurationProperties("home-control")
public record HomeControlProperties(@DefaultValue("./data") Path dataDir, @DefaultValue Discovery discovery) {

    /** {@code home-control.discovery.*}; SSDP keeps its own {@code home-control.ssdp.*}. */
    public record Discovery(@DefaultValue("true") boolean enabled) {
    }
}
