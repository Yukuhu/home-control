package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Both TV modules are add-ons: switched off (here with every other module), nothing of them is left, and the app still starts. */
class SmartTvModulesOffTest extends ModulesOffTest {

    @Test
    void noTvAdapterOrPairingExists() {
        assertThat(context.getBeansOfType(PromptPairing.class)).isEmpty();
        assertThat(context.getBeansOfType(WebOsAdapter.class)).isEmpty();
        assertThat(context.getBeansOfType(TizenAdapter.class)).isEmpty();
        assertThat(context.getBeansOfType(SsdpDiscovery.class)).hasSize(1);
    }
}
