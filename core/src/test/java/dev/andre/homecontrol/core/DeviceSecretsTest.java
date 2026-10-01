package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceSecretsTest {

    @Test
    void aReferenceIsSixteenHexCharactersAndNewEachTime() {
        assertThat(DeviceSecrets.newReference()).matches("[0-9a-f]{16}");
        assertThat(DeviceSecrets.newReference()).isNotEqualTo(DeviceSecrets.newReference());
    }
}
