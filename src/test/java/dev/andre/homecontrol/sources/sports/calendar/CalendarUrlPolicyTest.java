package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarUrlPolicyTest {

    private static CalendarUrlPolicy.HostResolver literal(String... addresses) {
        return host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "0.0.0.0", "169.254.169.254", "fe80::1", "224.0.0.251", "::ffff:127.0.0.1"})
    void blocksMachineAndLinkAddresses(String address) {
        CalendarUrlPolicy stubbed = new CalendarUrlPolicy(false, literal(address));
        var calendarUrl = URI.create("http://example.org/a.ics");
        assertThatThrownBy(() -> stubbed.checkAddress(calendarUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED)
                .hasMessageContaining("example.org").hasMessageContaining("belongs to this machine");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.20", "10.0.0.5", "172.16.3.4", "fd12:3456::1", "100.64.1.1", "93.184.216.34"})
    void allowsLanAndPublicAddresses(String address) {
        CalendarUrlPolicy stubbed = new CalendarUrlPolicy(false, literal(address));
        stubbed.checkAddress(URI.create("http://example.org/a.ics"));
    }

    @Test
    void blocksWhenAnyResolvedAddressIsBlocked() {
        CalendarUrlPolicy stubbed = new CalendarUrlPolicy(false, literal("93.184.216.34", "127.0.0.1"));
        var rebindingUrl = URI.create("http://rebind.example/a.ics");
        assertThatThrownBy(() -> stubbed.checkAddress(rebindingUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED);
    }

    @Test
    void loopbackCanBeAllowed() {
        CalendarUrlPolicy allowed = new CalendarUrlPolicy(true, literal("127.0.0.1"));
        allowed.checkAddress(URI.create("http://example.org/a.ics"));

        CalendarUrlPolicy metadataStillBlocked = new CalendarUrlPolicy(true, literal("169.254.169.254"));
        var calendarUrl = URI.create("http://example.org/a.ics");
        assertThatThrownBy(() -> metadataStillBlocked.checkAddress(calendarUrl))
                .isInstanceOf(CalendarFetchException.class);
    }

    @Test
    void unknownHostsAreUnreachable() {
        CalendarUrlPolicy stubbed = new CalendarUrlPolicy(false, host -> {
            throw new java.net.UnknownHostException(host);
        });
        var unresolvableUrl = URI.create("http://nowhere.invalid/a.ics");
        assertThatThrownBy(() -> stubbed.checkAddress(unresolvableUrl))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.UNREACHABLE)
                .hasMessage("Could not find nowhere.invalid");
    }

    @Test
    void vetsBracketedAndBareIpv6LiteralsAlike() {
        CalendarUrlPolicy lan = new CalendarUrlPolicy(false);

        assertThat(lan.addresses("[fd00::5]")).containsExactly(InetAddress.ofLiteral("fd00::5"));
        assertThat(lan.addresses("fd00::5")).containsExactly(InetAddress.ofLiteral("fd00::5"));
        assertThatThrownBy(() -> lan.addresses("[::1]"))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", ContentSourceException.Kind.BLOCKED);
    }
}
