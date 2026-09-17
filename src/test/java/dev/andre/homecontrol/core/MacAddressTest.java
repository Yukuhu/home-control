package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"a8:23:fe:01:02:03", "A8-23-FE-01-02-03", "a823.fe01.0203", "a823fe010203", " A8:23:FE:01:02:03 "})
    void normalisesTheUsualSpellings(String input) {
        assertThat(MacAddress.normalize(input)).isEqualTo("A8:23:FE:01:02:03");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "zz:23:fe:01:02:03", "a8:23:fe:01:02", "a8:23:fe:01:02:03:04"})
    void rejectsWhatIsNotAMac(String input) {
        assertThatThrownBy(() -> MacAddress.normalize(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingMacNamesAnExample() {
        assertThatThrownBy(() -> MacAddress.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A8:23:FE:01:02:03");
    }

    @Test
    void bytesAreTheSixOctets() {
        assertThat(MacAddress.bytes("A8:23:FE:01:02:03"))
                .containsExactly((byte) 0xA8, 0x23, (byte) 0xFE, 0x01, 0x02, 0x03);
    }
}
