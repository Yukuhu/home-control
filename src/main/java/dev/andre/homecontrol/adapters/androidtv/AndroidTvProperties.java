package dev.andre.homecontrol.adapters.androidtv;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("shield")
public record AndroidTvProperties(Path dataDir,
                               String keystorePassword,
                               boolean discoveryEnabled,
                               int staleTimeoutSeconds,
                               int reconnectInitialDelaySeconds,
                               int reconnectMaxDelaySeconds) {

    public Path keystoreFile() {
        return dataDir.resolve("keystore.p12");
    }

    public Path devicesFile() {
        return dataDir.resolve("devices.json");
    }

}
