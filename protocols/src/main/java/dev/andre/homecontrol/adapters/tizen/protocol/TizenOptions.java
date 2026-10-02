package dev.andre.homecontrol.adapters.tizen.protocol;

import java.time.Duration;

/** Where a Samsung TV's remote socket, REST API and DIAL server listen, the name it shows, and how long to wait. */
public record TizenOptions(int port, int restPort, int dialPort, String clientName, Duration connectTimeout,
                           Duration requestTimeout) {
}
