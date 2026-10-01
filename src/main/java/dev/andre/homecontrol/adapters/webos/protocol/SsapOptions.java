package dev.andre.homecontrol.adapters.webos.protocol;

import java.time.Duration;

/** Where an LG TV's SSAP sockets listen, and how long a connection waits for them. */
public record SsapOptions(int port, int securePort, Duration connectTimeout, Duration requestTimeout) {
}
