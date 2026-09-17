package dev.andre.homecontrol.adapters.upnp.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpnpTimeTest {

    @Test
    void readsUpnpDurations() {
        assertThat(UpnpTime.seconds("0:03:07")).isEqualTo(187.0);
        assertThat(UpnpTime.seconds("1:02:03.500")).isEqualTo(3723.5);
        assertThat(UpnpTime.seconds("00:00:05.1/4")).isEqualTo(5.25);
        assertThat(UpnpTime.seconds("+0:00:10")).isEqualTo(10.0);
        assertThat(UpnpTime.seconds("12:00:00")).isEqualTo(43200.0);
    }

    @Test
    void absentOrMalformedIsNull() {
        assertThat(UpnpTime.seconds(null)).isNull();
        assertThat(UpnpTime.seconds("")).isNull();
        assertThat(UpnpTime.seconds("NOT_IMPLEMENTED")).isNull();
        assertThat(UpnpTime.seconds("3:07")).isNull();
        assertThat(UpnpTime.seconds("0:00:05.1/0")).isNull();
    }
}
