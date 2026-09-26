package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
