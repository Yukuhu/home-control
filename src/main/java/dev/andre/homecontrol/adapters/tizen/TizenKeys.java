package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.RemoteKey;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Remote keys → Samsung key codes. POWER and PLAY_PAUSE need session state; MEDIA_NEXT/PREVIOUS have no code. */
final class TizenKeys {

    static final Set<RemoteKey> HANDLED_BY_SESSION = Set.of(RemoteKey.POWER, RemoteKey.PLAY_PAUSE);

    private static final Map<RemoteKey, String> CODES = new EnumMap<>(Map.ofEntries(
            Map.entry(RemoteKey.DPAD_UP, "KEY_UP"),
            Map.entry(RemoteKey.DPAD_DOWN, "KEY_DOWN"),
            Map.entry(RemoteKey.DPAD_LEFT, "KEY_LEFT"),
            Map.entry(RemoteKey.DPAD_RIGHT, "KEY_RIGHT"),
            Map.entry(RemoteKey.DPAD_CENTER, "KEY_ENTER"),
            Map.entry(RemoteKey.BACK, "KEY_RETURN"),
            Map.entry(RemoteKey.HOME, "KEY_HOME"),
            Map.entry(RemoteKey.MENU, "KEY_MENU"),
            Map.entry(RemoteKey.VOLUME_UP, "KEY_VOLUP"),
            Map.entry(RemoteKey.VOLUME_DOWN, "KEY_VOLDOWN"),
            Map.entry(RemoteKey.VOLUME_MUTE, "KEY_MUTE"),
            Map.entry(RemoteKey.MEDIA_STOP, "KEY_STOP"),
            Map.entry(RemoteKey.REWIND, "KEY_REWIND"),
            Map.entry(RemoteKey.FAST_FORWARD, "KEY_FF"),
            Map.entry(RemoteKey.INFO, "KEY_INFO"),
            Map.entry(RemoteKey.SETTINGS, "KEY_TOOLS"),
            Map.entry(RemoteKey.GUIDE, "KEY_GUIDE")));

    private TizenKeys() {
    }

    static Optional<String> code(RemoteKey key) {
        return Optional.ofNullable(CODES.get(key));
    }
}
