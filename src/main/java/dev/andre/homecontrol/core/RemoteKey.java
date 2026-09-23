package dev.andre.homecontrol.core;

/** The subset of Android key codes this remote exposes. */
public enum RemoteKey {

    DPAD_UP(19),
    DPAD_DOWN(20),
    DPAD_LEFT(21),
    DPAD_RIGHT(22),
    DPAD_CENTER(23),
    BACK(4),
    HOME(3),
    MENU(82),
    POWER(26),
    WAKEUP(224),
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    VOLUME_MUTE(164),
    PLAY_PAUSE(85),
    MEDIA_NEXT(87),
    MEDIA_PREVIOUS(88),
    MEDIA_STOP(86),
    REWIND(89),
    FAST_FORWARD(90),
    INFO(165),
    SETTINGS(176),
    GUIDE(172);

    private final int code;

    RemoteKey(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Whether this key accepts a start/end long press instead of just a short tap. */
    public boolean supportsLongPress() {
        return switch (this) {
            case DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME -> true;
            default -> false;
        };
    }
}
