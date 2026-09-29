package dev.andre.homecontrol.device;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HostAddressesTest {

    private static InetAddress address(String name, int last) {
        try {
            return InetAddress.getByAddress(name, new byte[] {10, 0, 0, (byte) last});
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void namesThatResolveToOneAddressAreTheSameHost() {
        HostAddresses addresses = HostAddresses.resolve(List.of("nas.lan", "10.0.0.5"),
                host -> Optional.of(address(host, 5)));

        assertThat(addresses.same("nas.lan", "10.0.0.5")).isTrue();
        assertThat(addresses.same("NAS.lan", "10.0.0.5")).isTrue();
    }

    @Test
    void aHostThatWasNotResolvedIsComparedByNameOnly() {
        HostAddresses addresses = HostAddresses.resolve(List.of("10.0.0.5"), host -> Optional.of(address(host, 5)));

        assertThat(addresses.same("new.lan", "10.0.0.5")).isFalse();
        assertThat(addresses.same("new.lan", "NEW.lan")).isTrue();
    }

    @Test
    void differentAddressesAreDifferentHosts() {
        HostAddresses addresses = HostAddresses.resolve(List.of("a.lan", "b.lan"),
                host -> Optional.of(address(host, host.startsWith("a") ? 5 : 6)));

        assertThat(addresses.same("a.lan", "b.lan")).isFalse();
        assertThat(addresses.same(null, "a.lan")).isFalse();
    }
}
