package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Both TV modules are add-ons: switched off, nothing of them is left, and the app still starts. */
@SpringBootTest(properties = {"home-control.webos.enabled=false", "home-control.tizen.enabled=false",
        "shield.data-dir=build/tmp/tv-modules-off-test"})
class SmartTvModulesOffTest {

    @Autowired
    ApplicationContext context;

    @Test
    void noTvAdapterOrPairingExists() {
        assertThat(context.getBeansOfType(PromptPairing.class)).isEmpty();
        assertThat(context.getBeansOfType(WebOsAdapter.class)).isEmpty();
        assertThat(context.getBeansOfType(TizenAdapter.class)).isEmpty();
        assertThat(context.getBeansOfType(SsdpDiscovery.class)).hasSize(1);
    }
}
