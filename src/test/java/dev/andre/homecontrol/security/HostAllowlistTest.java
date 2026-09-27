package dev.andre.homecontrol.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HostAllowlistTest {

    private final HostAllowlist hosts = new HostAllowlist(List.of("https://home.example.org", "http://proxy.example.net:8443"),
            List.of("remote.example.com", "*.tv.example.com", " ", ""));

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.10", "192.168.1.10:8080", "10.0.0.1:1", "127.0.0.1:65535",
            "[::1]", "[::1]:8080", "[fe80::1ff:fe23:4567:890a]:8080", "[2001:db8::1]",
            "localhost", "localhost:8080", "LocalHost:8080", "nas", "nas:8080", "home-control",
            "tv.local", "Living-Room.local:8080", "home-control.lan", "box.home.arpa:80", "svc.internal",
            "home.example.org", "home.example.org:443", "proxy.example.net", "proxy.example.net:8443",
            "remote.example.com", "remote.example.com:8080", "a.tv.example.com", "b.a.tv.example.com:8080"})
    void allowsLanNamesIpLiteralsAndConfiguredHosts(String host) {
        assertThat(hosts.allows(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "evil.example:8080", "192.168.1.10.nip.io", "attacker.com",
            "tv.local.evil.example", "evil.example.local.", "localhost.", "evil.example.",
            "tv.example.com", "other.remote.example.com", "xtv.example.com", "home.example.org.evil.example",
            "", " ", "[::1", "::1", "[::1]x", "[not-an-ip]:80", "[::1]:", "1.2.3.4:", "1.2.3.4:99999", "1.2.3.4:0",
            "host:abc", "user@localhost", "localhost/x", "a..local", ".local", "local.", "-bad.local", "bad-.lan",
            "300.1.1.1", "1.2.3.4.5", "tv.local:80:80", "tv\0.local", "t v.local"})
    void refusesEverythingElse(String host) {
        assertThat(hosts.allows(host)).isFalse();
    }

    @Test
    void aMissingHostIsRefused() {
        assertThat(hosts.allows(null)).isFalse();
    }

    @Test
    void worksWithoutAnyConfiguration() {
        HostAllowlist defaults = new HostAllowlist(null, null);

        assertThat(defaults.allows("192.168.1.10:8080")).isTrue();
        assertThat(defaults.allows("tv.local")).isTrue();
        assertThat(defaults.allows("home.example.org")).isFalse();
    }

    @Test
    void aMalformedConfiguredOriginIsIgnoredRatherThanAllowingEverything() {
        HostAllowlist odd = new HostAllowlist(List.of("not a uri", "null", "*"), List.of("*", "*.", "*.com."));

        assertThat(odd.allows("evil.example")).isFalse();
        assertThat(odd.allows("tv.local")).isTrue();
    }

    @Test
    void aBracketedHostMustBeAnIpv6LiteralWithAValidPortIfAny() {
        assertThat(hosts.allows("[::1]:1")).isTrue();
        assertThat(hosts.allows("[::1]:65535")).isTrue();
        assertThat(hosts.allows("[::1]:0")).isFalse();
        assertThat(hosts.allows("[::1]:65536")).isFalse();
        assertThat(hosts.allows("[::1]]")).isFalse();
        assertThat(hosts.allows("[localhost]")).isFalse();
        assertThat(hosts.allows("[192.168.1.10]:80")).isFalse();
        assertThat(hosts.allows("[]")).isFalse();
    }
}
