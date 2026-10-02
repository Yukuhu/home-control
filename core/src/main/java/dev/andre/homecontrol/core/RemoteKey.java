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

    /** The key as people call it, for messages: "Kitchen TV has no next track key", "… asked to press home". */
    public String label() {
        return switch (this) {
            case DPAD_UP -> "up";
            case DPAD_DOWN -> "down";
            case DPAD_LEFT -> "left";
            case DPAD_RIGHT -> "right";
            case DPAD_CENTER -> "OK";
            case BACK -> "back";
            case HOME -> "home";
            case MENU -> "menu";
            case POWER -> "power";
            case WAKEUP -> "wake up";
            case VOLUME_UP -> "volume up";
            case VOLUME_DOWN -> "volume down";
            case VOLUME_MUTE -> "mute";
            case PLAY_PAUSE -> "play/pause";
            case MEDIA_NEXT -> "next track";
            case MEDIA_PREVIOUS -> "previous track";
            case MEDIA_STOP -> "stop";
            case REWIND -> "rewind";
            case FAST_FORWARD -> "fast forward";
            case INFO -> "info";
            case SETTINGS -> "settings";
            case GUIDE -> "guide";
        };
    }

    /** Whether this key accepts a start/end long press instead of just a short tap. */
    public boolean supportsLongPress() {
        return switch (this) {
            case DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME -> true;
            default -> false;
        };
    }
}
