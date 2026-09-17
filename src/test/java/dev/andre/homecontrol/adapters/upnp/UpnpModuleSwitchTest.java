package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class UpnpModuleSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SsdpDiscovery.class, () -> new SsdpDiscovery(new SsdpProperties(false, "239.255.255.250", 1900, 1900, 60, 2)))
            .withUserConfiguration(UpnpConfiguration.class);

    @Test
    void isOnByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(UpnpAdapter.class).hasSingleBean(UpnpDiscovery.class));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("home-control.upnp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UpnpAdapter.class).doesNotHaveBean(UpnpDiscovery.class));
    }

    @Test
    void aNonsensicalIntervalFailsStartup() {
        runner.withPropertyValues("home-control.upnp.poll-interval-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
