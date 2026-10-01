package dev.andre.homecontrol.core;

/** The keys of the remote. Each adapter maps them onto its own device's codes. */
public enum RemoteKey {

    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_CENTER,
    BACK,
    HOME,
    MENU,
    POWER,
    WAKEUP,
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MUTE,
    PLAY_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_STOP,
    REWIND,
    FAST_FORWARD,
    INFO,
    SETTINGS,
    GUIDE;

    /** Whether this key accepts a start/end long press instead of just a short tap. */
    public boolean supportsLongPress() {
        return switch (this) {
            case DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME -> true;
            default -> false;
        };
    }
}
