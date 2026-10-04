package dev.andre.homecontrol.adapters.sonos.protocol;

import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SonosEndpointsTest {

    @Test
    void anEndpointIsTheSpeakersAddressAndPortWithThePath() throws MalformedURLException {
        assertThat(SonosEndpoints.endpoint("192.168.1.40", 1400, "/MediaRenderer/AVTransport/Control", "t").controlUrl())
                .isEqualTo(URI.create("http://192.168.1.40:1400/MediaRenderer/AVTransport/Control"));
        assertThat(SonosEndpoints.endpoint("fe80::1", 1400, "/x", "t").controlUrl())
                .isEqualTo(URI.create("http://[fe80::1]:1400/x"));
    }

    @Test
    void onlyAPlayerOnTheLocalNetworkIsALanLocation() {
        assertThat(SonosEndpoints.isLanLocation(URI.create("http://192.168.1.41:1400/xml/device_description.xml"), false))
                .isTrue();
        assertThat(SonosEndpoints.isLanLocation(URI.create("http://169.254.10.2:1400/x"), false)).isTrue();
        assertThat(SonosEndpoints.isLanLocation(URI.create("http://8.8.8.8:1400/x"), false)).isFalse();
        assertThat(SonosEndpoints.isLanLocation(URI.create("http://sonos.lan:1400/x"), false)).isFalse();
        assertThat(SonosEndpoints.isLanLocation(URI.create("https://192.168.1.41:1443/x"), false)).isFalse();
        assertThat(SonosEndpoints.isLanLocation(null, true)).isFalse();
    }

    @Test
    void aLoopbackLocationOnlyWhenTheAnsweringPlayerIsOnLoopback() {
        URI loopback = URI.create("http://127.0.0.1:1400/x");

        assertThat(SonosEndpoints.isLanLocation(loopback, false)).isFalse();
        assertThat(SonosEndpoints.isLanLocation(loopback, true)).isTrue();
    }

    @Test
    void loopbackIsAnsweredForAddressesWithoutALookup() {
        assertThat(SonosEndpoints.isLoopback("127.0.0.1")).isTrue();
        assertThat(SonosEndpoints.isLoopback("::1")).isTrue();
        assertThat(SonosEndpoints.isLoopback("192.168.1.41")).isFalse();
        assertThat(SonosEndpoints.isLoopback("localhost")).isFalse();
        assertThat(SonosEndpoints.isLoopback("1:2")).isFalse();
        assertThat(SonosEndpoints.isLoopback(null)).isFalse();
    }

    /** A host that would change the URL reaches nothing: here it would have been a page of this machine. */
    @Test
    void aHostThatWouldChangeTheUrlIsRefused() {
        assertThatThrownBy(() -> SonosEndpoints.endpoint("127.0.0.1/admin?", 1400, "/x", "t"))
                .isInstanceOf(MalformedURLException.class);
    }
}
