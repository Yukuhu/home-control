package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SonosModuleSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SsdpDiscovery.class, () -> new SsdpDiscovery(new SsdpProperties(false, "239.255.255.250", 1900, 1900, 60, 2)))
            .withUserConfiguration(SonosConfiguration.class);

    @Test
    void isOnByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(SonosAdapter.class).hasSingleBean(SonosDiscovery.class));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("home-control.sonos.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SonosAdapter.class).doesNotHaveBean(SonosDiscovery.class));
    }

    @Test
    void aNonsensicalIntervalFailsStartup() {
        runner.withPropertyValues("home-control.sonos.command-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
