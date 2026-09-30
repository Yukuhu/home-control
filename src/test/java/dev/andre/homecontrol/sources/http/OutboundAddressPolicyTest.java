package dev.andre.homecontrol.sources.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where a content source may connect: the LAN and the internet, never this machine's link or its metadata. */
class OutboundAddressPolicyTest {

    private static OutboundAddressPolicy resolvingTo(boolean allowLoopback, String... addresses) {
        return new OutboundAddressPolicy(allowLoopback, host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.ofLiteral(addresses[i]);
            }
            return resolved;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "192.168.1.20", "10.0.0.5", "172.16.3.4", "100.64.1.1", "2001:db8::1",
            "fd00::5", "fd12:3456::1"})
    void allowsTheInternetAndTheLan(String address) throws UnknownHostException {
        assertThat(resolvingTo(false, address).addresses("media.example")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "169.254.169.254", "fe80::1", "224.0.0.251", "ff02::1",
            "127.0.0.1", "::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254"})
    void refusesThisMachineItsLinkAndMulticast(String address) {
        assertThatThrownBy(() -> resolvingTo(false, address).addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "::ffff:127.0.0.1"})
    void allowsLoopbackOnlyWhenAsked(String address) throws UnknownHostException {
        assertThat(resolvingTo(true, address).addresses("media.example")).hasSize(1);
    }

    /** The JDK turns a mapped literal into an IPv4 address, but a resolver can hand back the IPv6 form itself. */
    @Test
    void judgesAnIpv4MappedIpv6AddressAsTheIpv4AddressItCarries() throws UnknownHostException {
        byte[] mappedLoopback = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 127, 0, 0, 1};
        InetAddress mapped = Inet6Address.getByAddress(null, mappedLoopback, (NetworkInterface) null);

        assertThatThrownBy(() -> new OutboundAddressPolicy(false, host -> new InetAddress[] {mapped}).addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
        assertThat(new OutboundAddressPolicy(true, host -> new InetAddress[] {mapped}).addresses("media.example")).hasSize(1);
    }

    @Test
    void oneRefusedAddressRefusesTheHost() {
        assertThatThrownBy(() -> resolvingTo(false, "93.184.216.34", "127.0.0.1").addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @Test
    void judgesLiteralsWithoutAskingDns() throws UnknownHostException {
        AtomicInteger lookups = new AtomicInteger();
        var policy = new OutboundAddressPolicy(false, host -> {
            lookups.incrementAndGet();
            throw new UnknownHostException(host);
        });

        assertThat(policy.addresses("192.168.1.20")).hasSize(1);
        assertThatThrownBy(() -> policy.addresses("169.254.169.254"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
        assertThat(lookups).hasValue(0);
    }

    @Test
    void judgesBracketedIpv6Literals() throws UnknownHostException {
        assertThat(resolvingTo(true).addresses("[::1]")).hasSize(1);
        assertThatThrownBy(() -> resolvingTo(true).addresses("[fe80::1]"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
        assertThat(OutboundAddressPolicy.isLiteral("[::1]")).isTrue();
        assertThat(OutboundAddressPolicy.isLiteral("media.example")).isFalse();
    }

    @Test
    void anUnknownHostIsNotBlocked() {
        assertThatThrownBy(() -> resolvingTo(false).addresses("nowhere.example"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @Test
    void neverHandsOutTheResolversOwnArray() throws UnknownHostException {
        InetAddress[] kept = {InetAddress.ofLiteral("192.168.1.20")};
        var policy = new OutboundAddressPolicy(false, host -> kept);

        InetAddress[] checked = policy.addresses("media.example");
        kept[0] = InetAddress.ofLiteral("127.0.0.1");

        assertThat(checked[0].getHostAddress()).isEqualTo("192.168.1.20");
    }

    @Test
    void allowingLoopbackNeverAllowsLinkLocal() {
        assertThatThrownBy(() -> resolvingTo(true, "169.254.1.1").addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @Test
    void looksAHostUpOnce() {
        AtomicInteger lookups = new AtomicInteger();
        var policy = new OutboundAddressPolicy(false, host -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.ofLiteral("192.168.1.2"), InetAddress.ofLiteral("127.0.0.1")};
        });

        assertThatThrownBy(() -> policy.addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
        assertThat(lookups).hasValue(1);
    }
}
