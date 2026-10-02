package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceUrisTest {

    @Test
    void aHostNameAndPortMakeTheAuthority() throws MalformedURLException {
        assertThat(DeviceUris.of("http", "tv.local", 8001, "/api/v2/"))
                .isEqualTo(URI.create("http://tv.local:8001/api/v2/"));
    }

    @Test
    void anIpv6AddressGetsItsBrackets() throws MalformedURLException {
        assertThat(DeviceUris.of("ws", "fe80::1", 3000, "")).isEqualTo(URI.create("ws://[fe80::1]:3000"));
    }

    @Test
    void aLinkLocalAddressKeepsItsZoneInsideTheBrackets() throws MalformedURLException {
        assertThat(DeviceUris.of("http", "fe80::1%eth0", 8080, "/x")).hasToString("http://[fe80::1%eth0]:8080/x");
    }

    @Test
    void anIpv4MappedAddressGetsItsBrackets() throws MalformedURLException {
        assertThat(DeviceUris.of("http", "::ffff:192.168.1.10", 8080, "/x"))
                .isEqualTo(URI.create("http://[::ffff:192.168.1.10]:8080/x"));
    }

    @Test
    void anEncodedPathAndQueryStayAsGiven() throws MalformedURLException {
        String pathAndQuery = "/api/v2/channels/samsung.remote.control?name=SG9tZQ%3D%3D&token=a%2Bb";

        assertThat(DeviceUris.of("wss", "192.0.2.5", 8002, pathAndQuery))
                .hasToString("wss://192.0.2.5:8002" + pathAndQuery);
    }

    @Test
    void aHostThatWouldChangeTheUrlIsRefused() {
        // A slash, a question mark or an at sign would point the request at another host, path or user.
        for (String host : new String[] {"evil.example/x?", "tv.local?x", "user@tv.local", "tv.local#x"}) {
            assertThatThrownBy(() -> DeviceUris.of("http", host, 80, "/"))
                    .as(host)
                    .isInstanceOf(MalformedURLException.class);
        }
    }

    @Test
    void aHostNoUrlCanCarryIsRefused() {
        assertThatThrownBy(() -> DeviceUris.of("http", "not a host", 80, ""))
                .isInstanceOf(MalformedURLException.class)
                .hasMessageContaining("not a host");
    }

    @Test
    void aHostAUrlCannotCarryIsAMalformedUrlNotABug() {
        // Valid names for the setup page, but no URL can carry an underscore or a numeric last label: the device is
        // unreachable, which the sessions and pairing already report, rather than a programming error.
        for (String host : new String[] {"samsung_tv.fritz.box", "tv.123", "192.0.2.5."}) {
            assertThatThrownBy(() -> DeviceUris.of("ws", host, 3000, ""))
                    .as(host)
                    .isInstanceOf(MalformedURLException.class);
        }
    }

    @Test
    void aHostTheSetupPageWouldRefuseIsRefused() {
        // A URL carries a 64-character label, but no host name has one: the same check as the setup page applies.
        String host = "a".repeat(64) + ".local";

        assertThatThrownBy(() -> DeviceUris.of("http", host, 80, "/"))
                .isInstanceOf(MalformedURLException.class);
    }
}
