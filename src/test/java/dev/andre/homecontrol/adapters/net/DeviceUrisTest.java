package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceUrisTest {

    @Test
    void aHostNameAndPortMakeTheAuthority() {
        assertThat(DeviceUris.of("http", "tv.local", 8001, "/api/v2/"))
                .isEqualTo(URI.create("http://tv.local:8001/api/v2/"));
    }

    @Test
    void anIpv6AddressGetsItsBrackets() {
        assertThat(DeviceUris.of("ws", "fe80::1", 3000, "")).isEqualTo(URI.create("ws://[fe80::1]:3000"));
    }

    @Test
    void anEncodedPathAndQueryStayAsGiven() {
        String pathAndQuery = "/api/v2/channels/samsung.remote.control?name=SG9tZQ%3D%3D&token=a%2Bb";

        assertThat(DeviceUris.of("wss", "192.0.2.5", 8002, pathAndQuery).toString())
                .isEqualTo("wss://192.0.2.5:8002" + pathAndQuery);
    }

    @Test
    void aHostThatWouldChangeTheUrlIsRefused() {
        // A slash, a question mark or an at sign would point the request at another host, path or user.
        for (String host : new String[] {"evil.example/x?", "tv.local?x", "user@tv.local", "tv.local#x"}) {
            assertThatThrownBy(() -> DeviceUris.of("http", host, 80, "/"))
                    .as(host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aHostNoUrlCanCarryIsRefused() {
        assertThatThrownBy(() -> DeviceUris.of("http", "not a host", 80, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a host");
    }
}
