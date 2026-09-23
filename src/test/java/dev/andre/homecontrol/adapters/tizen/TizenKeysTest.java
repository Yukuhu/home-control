package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TizenKeysTest {

    @ParameterizedTest
    @CsvSource({"DPAD_UP,KEY_UP", "DPAD_DOWN,KEY_DOWN", "DPAD_LEFT,KEY_LEFT", "DPAD_RIGHT,KEY_RIGHT",
            "DPAD_CENTER,KEY_ENTER", "BACK,KEY_RETURN", "HOME,KEY_HOME", "MENU,KEY_MENU", "VOLUME_UP,KEY_VOLUP",
            "VOLUME_DOWN,KEY_VOLDOWN", "VOLUME_MUTE,KEY_MUTE", "MEDIA_STOP,KEY_STOP", "REWIND,KEY_REWIND",
            "FAST_FORWARD,KEY_FF", "INFO,KEY_INFO", "SETTINGS,KEY_TOOLS", "GUIDE,KEY_GUIDE"})
    void mapsRemoteKeysToSamsungKeyCodes(RemoteKey key, String code) {
        assertThat(TizenKeys.code(key)).contains(code);
    }

    @Test
    void everyRemoteKeyIsDecided() {
        Set<RemoteKey> withoutCode = Set.of(RemoteKey.MEDIA_NEXT, RemoteKey.MEDIA_PREVIOUS, RemoteKey.WAKEUP);
        for (RemoteKey key : RemoteKey.values()) {
            assertThat(TizenKeys.code(key).isPresent() || TizenKeys.HANDLED_BY_SESSION.contains(key)
                    || withoutCode.contains(key))
                    .as("Tizen handling of %s", key)
                    .isTrue();
        }
    }
}
