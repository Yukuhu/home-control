package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.CodePairing;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.androidtv.enabled=false}: no adapter, pairing, keystore or setup form. */
class AndroidTvModuleSwitchTest extends ModulesOffTest {

    @Autowired
    DeviceManager devices;

    @Test
    void theModuleLeavesNoBeans() {
        assertThat(context.getBeanNamesForType(AndroidTvAdapter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PairingService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CodePairing.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CertificateStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MdnsDiscovery.class)).isEmpty();
    }

    @Test
    void theSetupPageOffersNoAndroidTvPairingAndItsRoutesAreGone() throws Exception {
        String setup = mockMvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(setup).doesNotContain("Add an Android TV by address");
        mockMvc.perform(post("/setup/pair").param("host", "192.0.2.1")).andExpect(status().isNotFound());
        mockMvc.perform(post("/setup/code").param("code", "123456")).andExpect(status().isNotFound());
    }

    @Test
    void aDeviceWithAnAndroidTvEntryStillRenders() throws Exception {
        devices.adopt(new Device("old-shield", "Old Shield", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of()), Instant.now()));
        try {
            mockMvc.perform(get("/").param("device", "old-shield")).andExpect(status().isOk());
            mockMvc.perform(get("/setup")).andExpect(status().isOk());
        } finally {
            devices.forget("old-shield");
        }
    }
}
