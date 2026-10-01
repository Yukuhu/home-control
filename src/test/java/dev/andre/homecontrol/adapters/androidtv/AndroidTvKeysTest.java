package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class AndroidTvKeysTest {

    /** The Android key codes each key sent while they were part of core.RemoteKey. */
    private static final Map<RemoteKey, Integer> SENT = Map.ofEntries(
            entry(RemoteKey.DPAD_UP, 19),
            entry(RemoteKey.DPAD_DOWN, 20),
            entry(RemoteKey.DPAD_LEFT, 21),
            entry(RemoteKey.DPAD_RIGHT, 22),
            entry(RemoteKey.DPAD_CENTER, 23),
            entry(RemoteKey.BACK, 4),
            entry(RemoteKey.HOME, 3),
            entry(RemoteKey.MENU, 82),
            entry(RemoteKey.POWER, 26),
            entry(RemoteKey.WAKEUP, 224),
            entry(RemoteKey.VOLUME_UP, 24),
            entry(RemoteKey.VOLUME_DOWN, 25),
            entry(RemoteKey.VOLUME_MUTE, 164),
            entry(RemoteKey.PLAY_PAUSE, 85),
            entry(RemoteKey.MEDIA_NEXT, 87),
            entry(RemoteKey.MEDIA_PREVIOUS, 88),
            entry(RemoteKey.MEDIA_STOP, 86),
            entry(RemoteKey.REWIND, 89),
            entry(RemoteKey.FAST_FORWARD, 90),
            entry(RemoteKey.INFO, 165),
            entry(RemoteKey.SETTINGS, 176),
            entry(RemoteKey.GUIDE, 172));

    @Test
    void everyKeySendsTheAndroidKeyCodeItAlwaysSent() {
        assertThat(SENT).containsOnlyKeys(RemoteKey.values());
        SENT.forEach((key, code) -> assertThat(AndroidTvKeys.code(key).getNumber()).as(key.name()).isEqualTo(code));
    }

    @Test
    void pressesMapToTheirDirections() {
        assertThat(AndroidTvKeys.direction(KeyPress.SHORT)).isEqualTo(RemoteDirection.SHORT);
        assertThat(AndroidTvKeys.direction(KeyPress.START_LONG)).isEqualTo(RemoteDirection.START_LONG);
        assertThat(AndroidTvKeys.direction(KeyPress.END_LONG)).isEqualTo(RemoteDirection.END_LONG);
    }
}
