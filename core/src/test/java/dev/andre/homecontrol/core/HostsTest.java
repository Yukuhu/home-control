package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostsTest {

    @Test
    void theSameNameMatchesIgnoringCase() {
        assertThat(Hosts.same("Living-Room.local", "living-room.LOCAL")).isTrue();
        assertThat(Hosts.same("192.168.1.50", "192.168.1.50")).isTrue();
    }

    @Test
    void differentSpellingsOfOneAddressMatch() {
        assertThat(Hosts.same("::ffff:192.168.1.50", "192.168.1.50")).isTrue();
        assertThat(Hosts.same("fe80:0:0:0:0:0:0:1", "fe80::1")).isTrue();
    }

    @Test
    void differentAddressesDoNotMatch() {
        assertThat(Hosts.same("192.168.1.50", "192.168.1.51")).isFalse();
        assertThat(Hosts.same("fe80::1", "fe80::2")).isFalse();
    }

    @Test
    void aMissingOrUnresolvableHostMatchesNothing() {
        assertThat(Hosts.same(null, "192.168.1.50")).isFalse();
        assertThat(Hosts.same("192.168.1.50", null)).isFalse();
        assertThat(Hosts.same(null, null)).isFalse();
        assertThat(Hosts.same("no-such-tv.invalid", "192.168.1.50")).isFalse();
    }

    @Test
    void namesAndAddressesAreValidHosts() {
        assertThat(java.util.List.of("192.168.1.50", "127.0.0.1", "localhost", "Living-Room.local", "tv.lan.",
                "shield_tv.fritz.box", "fe80::1", "::1", "2001:db8::8a2e:370:7334", "::ffff:192.168.1.50", "fe80::1%eth0"))
                .allMatch(Hosts::isValid);
    }

    @Test
    void anythingThatCouldChangeWhereARequestGoesIsNotAHost() {
        assertThat(java.util.Arrays.asList(null, "", " ", "evil.example/x?", "127.0.0.1/admin?a=", "a@evil.example",
                "tv.local#fragment", "tv.local:8080", "[fe80::1]", "tv .local", "tv.local\n", "tv\\evil", "-tv.local",
                "tv..local", "fe80::1%eth0/x", "fe80::1%", "a:b", "x".repeat(254), "tv.%2e.local"))
                .noneMatch(Hosts::isValid);
    }

    @Test
    void theAuthorityOfAUrlIsTheHostWithAnIpv6AddressInBrackets() {
        assertThat(Hosts.authority("192.168.1.50")).isEqualTo("192.168.1.50");
        assertThat(Hosts.authority("Living-Room.local")).isEqualTo("Living-Room.local");
        assertThat(Hosts.authority("fe80::1")).isEqualTo("[fe80::1]");
    }

    @Test
    void noAuthorityIsMadeOfWhatIsNotAHost() {
        assertThatThrownBy(() -> Hosts.authority("127.0.0.1/admin?a="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a host name or an IP address: 127.0.0.1/admin?a=");
        assertThatThrownBy(() -> Hosts.authority(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
