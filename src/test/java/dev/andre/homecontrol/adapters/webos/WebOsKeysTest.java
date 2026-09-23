package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WebOsKeysTest {

    @ParameterizedTest
    @CsvSource({"DPAD_UP,UP", "DPAD_DOWN,DOWN", "DPAD_LEFT,LEFT", "DPAD_RIGHT,RIGHT", "DPAD_CENTER,ENTER",
            "BACK,BACK", "HOME,HOME", "MENU,MENU", "MEDIA_STOP,STOP", "REWIND,REWIND",
            "FAST_FORWARD,FASTFORWARD", "INFO,INFO", "SETTINGS,QMENU", "GUIDE,GUIDE"})
    void mapsNavigationAndMediaKeysToPointerSocketButtons(RemoteKey key, String button) {
        assertThat(WebOsKeys.button(key)).contains(button);
    }

    @Test
    void sessionHandledKeysHaveNoButton() {
        assertThat(WebOsKeys.HANDLED_BY_SESSION).containsExactlyInAnyOrder(
                RemoteKey.POWER, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.VOLUME_MUTE, RemoteKey.PLAY_PAUSE);
        WebOsKeys.HANDLED_BY_SESSION.forEach(key -> assertThat(WebOsKeys.button(key)).isEmpty());
    }

    @Test
    void everyRemoteKeyIsDecided() {
        Set<RemoteKey> withoutButton = Set.of(RemoteKey.MEDIA_NEXT, RemoteKey.MEDIA_PREVIOUS, RemoteKey.WAKEUP);
        for (RemoteKey key : RemoteKey.values()) {
            assertThat(WebOsKeys.button(key).isPresent()
                    || WebOsKeys.HANDLED_BY_SESSION.contains(key)
                    || withoutButton.contains(key))
                    .as("webOS handling of %s", key)
                    .isTrue();
        }
    }
}
