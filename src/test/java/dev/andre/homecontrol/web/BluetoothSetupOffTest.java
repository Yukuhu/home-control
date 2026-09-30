package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the module off, {@code BluetoothSetupController} and {@code BluetoothSetupSection} are not wired up at all. */
class BluetoothSetupOffTest extends ModulesOffTest {

    @Test
    void theSectionIsAbsent() throws Exception {
        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"bluetooth\""))));
    }

    @Test
    void thePostEndpointsDoNotExist() throws Exception {
        mockMvc.perform(post("/setup/bluetooth/scan")).andExpect(status().isNotFound());
    }
}
