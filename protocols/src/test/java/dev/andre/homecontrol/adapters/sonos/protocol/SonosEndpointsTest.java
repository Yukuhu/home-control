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

    /** A host that would change the URL reaches nothing: here it would have been a page of this machine. */
    @Test
    void aHostThatWouldChangeTheUrlIsRefused() {
        assertThatThrownBy(() -> SonosEndpoints.endpoint("127.0.0.1/admin?", 1400, "/x", "t"))
                .isInstanceOf(MalformedURLException.class);
    }
}
