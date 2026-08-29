package dev.andre.shield.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MdnsDiscoveryTest {

    @Test
    void mapsAResolvedServiceToADevice() throws Exception {
        InetAddress address = InetAddress.getByName("192.168.1.50");

        assertThat(MdnsDiscovery.toDevice("Living Room Shield", new InetAddress[]{address}, 6466))
                .contains(new DiscoveredDevice("Living Room Shield", "192.168.1.50", 6466));
    }

    @Test
    void ignoresAServiceWithNoAddress() {
        assertThat(MdnsDiscovery.toDevice("Ghost", new InetAddress[0], 6466)).isEmpty();
    }

    @Test
    void ignoresAServiceWithNoPort() throws Exception {
        InetAddress address = InetAddress.getByName("192.168.1.50");

        assertThat(MdnsDiscovery.toDevice("Portless", new InetAddress[]{address}, 0)).isEmpty();
    }

    @Test
    void usesTheAndroidTvRemoteServiceType() {
        assertThat(MdnsDiscovery.SERVICE_TYPE).isEqualTo("_androidtvremote2._tcp.local.");
    }

    /**
     * Real multicast round trip. Disabled by default: Docker bridge networks and most CI
     * runners drop mDNS. Run with: ./gradlew test -Dmdns.tests=true
     */
    @Test
    @EnabledIfSystemProperty(named = "mdns.tests", matches = "true")
    void findsAServiceAdvertisedOnTheLocalNetwork() throws Exception {
        try (JmDNS advertiser = JmDNS.create(InetAddress.getLocalHost());
             MdnsDiscovery discovery = new MdnsDiscovery(true)) {

            advertiser.registerService(ServiceInfo.create(
                    MdnsDiscovery.SERVICE_TYPE, "Fake Shield", 6466, "test"));
            discovery.start();

            await().until(() -> discovery.devices().stream()
                    .anyMatch(device -> device.name().contains("Fake Shield")));
        }
    }
}
