package dev.andre.shield;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("shield")
public record ShieldProperties(Path dataDir,
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
