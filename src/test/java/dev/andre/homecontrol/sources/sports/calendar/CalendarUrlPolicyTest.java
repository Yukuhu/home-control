package dev.andre.homecontrol.sources.sports.calendar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarUrlPolicyTest {

    private final CalendarUrlPolicy policy = new CalendarUrlPolicy(false);

    private static CalendarUrlPolicy.HostResolver literal(String... addresses) {
        return host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        };
    }

    @Test
    void acceptsHttpHttpsAndWebcal() {
        assertThat(policy.parse("https://calendar.example.org/a.ics"))
                .isEqualTo(URI.create("https://calendar.example.org/a.ics"));
        assertThat(policy.parse("webcal://fixtur.es/de/team.ics?x=1"))
                .isEqualTo(URI.create("https://fixtur.es/de/team.ics?x=1"));
        assertThat(policy.parse("WEBCALS://Example.org/x")).isEqualTo(URI.create("https://Example.org/x"));
        assertThat(policy.parse("http://192.168.1.20:5232/user/sport/"))
                .isEqualTo(URI.create("http://192.168.1.20:5232/user/sport/"));
    }

    @Test
    void rejectsBadLinks() {
        assertThatThrownBy(() -> policy.parse(" ")).hasMessage("Enter a calendar link");
        assertThatThrownBy(() -> policy.parse("a".repeat(2049))).hasMessage("That calendar link is too long");
        assertThatThrownBy(() -> policy.parse("ftp://example.org/a.ics")).hasMessage("Use an http, https or webcal link");
        assertThatThrownBy(() -> policy.parse("https://user:pw@example.org/a.ics"))
                .hasMessage("Links with a user name or password are not supported; use the calendar's secret link instead");
        assertThatThrownBy(() -> policy.parse("https:///a.ics")).hasMessage("That is not a valid link");
        assertThatThrownBy(() -> policy.parse("not a url")).hasMessage("That is not a valid link");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "0.0.0.0", "169.254.169.254", "fe80::1", "224.0.0.251", "::ffff:127.0.0.1"})
    void blocksMachineAndLinkAddresses(String address) {
        CalendarUrlPolicy stubbed = new CalendarUrlPolicy(false, literal(address));
        var preparedArg53_0 = URI.create("http://example.org/a.ics");
        assertThatThrownBy(() -> stubbed.checkAddress(preparedArg53_0))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BLOCKED)
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
        var preparedArg69_0 = URI.create("http://rebind.example/a.ics");
        assertThatThrownBy(() -> stubbed.checkAddress(preparedArg69_0))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.BLOCKED);
    }

    @Test
    void loopbackCanBeAllowed() {
        CalendarUrlPolicy allowed = new CalendarUrlPolicy(true, literal("127.0.0.1"));
        allowed.checkAddress(URI.create("http://example.org/a.ics"));

        CalendarUrlPolicy metadataStillBlocked = new CalendarUrlPolicy(true, literal("169.254.169.254"));
        var preparedArg80_0 = URI.create("http://example.org/a.ics");
        assertThatThrownBy(() -> metadataStillBlocked.checkAddress(preparedArg80_0))
                .isInstanceOf(CalendarFetchException.class);
    }

    @Test
    void unknownHostsAreUnreachable() {
        CalendarUrlPolicy stubbed = new CalendarUrlPolicy(false, host -> {
            throw new java.net.UnknownHostException(host);
        });
        var preparedArg89_0 = URI.create("http://nowhere.invalid/a.ics");
        assertThatThrownBy(() -> stubbed.checkAddress(preparedArg89_0))
                .isInstanceOf(CalendarFetchException.class)
                .hasFieldOrPropertyWithValue("kind", CalendarFetchException.Kind.UNREACHABLE)
                .hasMessage("Could not find nowhere.invalid");
    }
}
