package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteKeyTest {

    @Test
    void everyKeyHasANameForPeople() {
        for (RemoteKey key : RemoteKey.values()) {
            assertThat(key.label()).as(key.name()).isNotBlank().doesNotContain("_").isNotEqualTo(key.name());
        }
        assertThat(RemoteKey.VOLUME_UP.label()).isEqualTo("volume up");
        assertThat(RemoteKey.DPAD_CENTER.label()).isEqualTo("OK");
        assertThat(RemoteKey.PLAY_PAUSE.label()).isEqualTo("play/pause");
        assertThat(RemoteKey.MEDIA_NEXT.label()).isEqualTo("next track");
    }
}
