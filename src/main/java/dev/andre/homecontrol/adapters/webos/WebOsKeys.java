package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.RemoteKey;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Remote keys → pointer-socket button names (aiowebostv {@code buttons.py}). POWER, the volume keys
 * and PLAY_PAUSE are handled by the session through SSAP or a toggle; MEDIA_NEXT/PREVIOUS have no
 * webOS button.
 */
final class WebOsKeys {

    static final Set<RemoteKey> HANDLED_BY_SESSION = Set.of(
            RemoteKey.POWER, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.VOLUME_MUTE, RemoteKey.PLAY_PAUSE);

    private static final Map<RemoteKey, String> BUTTONS = new EnumMap<>(Map.ofEntries(
            Map.entry(RemoteKey.DPAD_UP, "UP"),
            Map.entry(RemoteKey.DPAD_DOWN, "DOWN"),
            Map.entry(RemoteKey.DPAD_LEFT, "LEFT"),
            Map.entry(RemoteKey.DPAD_RIGHT, "RIGHT"),
            Map.entry(RemoteKey.DPAD_CENTER, "ENTER"),
            Map.entry(RemoteKey.BACK, "BACK"),
            Map.entry(RemoteKey.HOME, "HOME"),
            Map.entry(RemoteKey.MENU, "MENU"),
            Map.entry(RemoteKey.MEDIA_STOP, "STOP"),
            Map.entry(RemoteKey.REWIND, "REWIND"),
            Map.entry(RemoteKey.FAST_FORWARD, "FASTFORWARD"),
            Map.entry(RemoteKey.INFO, "INFO"),
            Map.entry(RemoteKey.SETTINGS, "QMENU"),
            Map.entry(RemoteKey.GUIDE, "GUIDE")));

    private WebOsKeys() {
    }

    static Optional<String> button(RemoteKey key) {
        return Optional.ofNullable(BUTTONS.get(key));
    }
}
