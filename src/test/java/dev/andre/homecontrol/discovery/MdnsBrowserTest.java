package dev.andre.homecontrol.discovery;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MdnsBrowserTest {

    @Test
    void mapsAResolutionAndPrefersAnIpv4Address() throws Exception {
        InetAddress v6 = InetAddress.getByName("fe80::1");
        InetAddress v4 = InetAddress.getByName("192.168.1.60");

        MdnsBrowser.MdnsService service = MdnsBrowser.toService("_googlecast._tcp.local.", "Chromecast-abc",
                new InetAddress[]{v6, v4}, 8009, Map.of("fn", "Kitchen")).orElseThrow();

        assertThat(service.host()).contains("192.168.1.60");
        assertThat(service.txt()).containsEntry("fn", "Kitchen");
    }

    @Test
    void ignoresAResolutionWithoutAddressOrPort() throws Exception {
        assertThat(MdnsBrowser.toService("t", "n", new InetAddress[0], 8009, Map.of())).isEmpty();
        assertThat(MdnsBrowser.toService("t", "n", new InetAddress[]{InetAddress.getByName("10.0.0.1")}, 0, Map.of()))
                .isEmpty();
    }

    @Test
    void dispatchesOnlyToListenersOfTheResolvedType() throws Exception {
        MdnsBrowser browser = new MdnsBrowser(false);
        List<String> cast = new CopyOnWriteArrayList<>();
        List<String> androidTv = new CopyOnWriteArrayList<>();
        browser.browse("_googlecast._tcp.local.", listener(cast));
        browser.browse("_androidtvremote2._tcp.local.", listener(androidTv));

        browser.dispatchResolved(new MdnsBrowser.MdnsService("_googlecast._tcp.local.", "Chromecast-abc",
                List.of(InetAddress.getByName("10.0.0.9")), 8009, Map.of()));
        browser.dispatchRemoved("_googlecast._tcp.local.", "Chromecast-abc");

        assertThat(cast).containsExactly("resolved Chromecast-abc", "removed Chromecast-abc");
        assertThat(androidTv).isEmpty();
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() throws Exception {
        MdnsBrowser browser = new MdnsBrowser(false);
        List<String> seen = new CopyOnWriteArrayList<>();
        browser.browse("t", new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void removed(String serviceType, String name) {
            }
        });
        browser.browse("t", listener(seen));

        browser.dispatchResolved(new MdnsBrowser.MdnsService("t", "x", List.of(InetAddress.getByName("10.0.0.9")), 1, Map.of()));

        assertThat(seen).containsExactly("resolved x");
    }

    private static MdnsBrowser.Listener listener(List<String> seen) {
        return new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                seen.add("resolved " + service.name());
            }

            @Override
            public void removed(String serviceType, String name) {
                seen.add("removed " + name);
            }
        };
    }
}
