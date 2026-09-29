package dev.andre.homecontrol;

import dev.andre.homecontrol.adapters.cast.CastAdapter;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for {@code home-control.cast.enabled=false}: the module is a home-control device add-on, not a build-time
 * flavour, so a {@code devices.json} that still carries a {@code cast} entry from before it was switched off must not
 * bring back any Cast capability or control. That an Android TV box keeps its own controls then is
 * {@code DevicesTest.anEntryForAnAdapterThatIsSwitchedOffAddsNothing}: every module is off here, Android TV too.
 */
class CastDisabledSmokeTest extends ModulesOffTest {

    @Autowired
    DeviceQueries devices;

    @Autowired
    DeviceEnrollment enrollment;

    /** The modules-off context is shared: a device adopted here would stay for the next test class. */
    @AfterEach
    void forgetTheDevice() {
        enrollment.forget("shield-c");
    }

    @Test
    void theCastAdapterIsNotWiredUp() {
        assertThat(context.getBeanNamesForType(CastAdapter.class)).isEmpty();
    }

    @Test
    void aDeviceThatStillCarriesAStaleCastEntryHasNoCastCapabilitiesOrControls() throws Exception {
        Device shield = new Device("shield-c", "Shield C", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of(), "cast", Map.of("port", "8009")), Instant.now());
        enrollment.adopt(shield);

        assertThat(devices.capabilities("shield-c")).doesNotContain(Capability.CAST_RECEIVER);

        mockMvc.perform(get("/").param("device", "shield-c"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<h2>Cast</h2>"))))
                .andExpect(content().string(not(containsString("id=\"volume-shield-c\""))));
    }
}
