package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteKeyCode;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;

/** The Android key code and press direction Remote v2 sends for each key of the remote. */
final class AndroidTvKeys {

    private AndroidTvKeys() {
    }

    static RemoteKeyCode code(RemoteKey key) {
        return switch (key) {
            case DPAD_UP -> RemoteKeyCode.KEYCODE_DPAD_UP;
            case DPAD_DOWN -> RemoteKeyCode.KEYCODE_DPAD_DOWN;
            case DPAD_LEFT -> RemoteKeyCode.KEYCODE_DPAD_LEFT;
            case DPAD_RIGHT -> RemoteKeyCode.KEYCODE_DPAD_RIGHT;
            case DPAD_CENTER -> RemoteKeyCode.KEYCODE_DPAD_CENTER;
            case BACK -> RemoteKeyCode.KEYCODE_BACK;
            case HOME -> RemoteKeyCode.KEYCODE_HOME;
            case MENU -> RemoteKeyCode.KEYCODE_MENU;
            case POWER -> RemoteKeyCode.KEYCODE_POWER;
            case WAKEUP -> RemoteKeyCode.KEYCODE_WAKEUP;
            case VOLUME_UP -> RemoteKeyCode.KEYCODE_VOLUME_UP;
            case VOLUME_DOWN -> RemoteKeyCode.KEYCODE_VOLUME_DOWN;
            case VOLUME_MUTE -> RemoteKeyCode.KEYCODE_VOLUME_MUTE;
            case PLAY_PAUSE -> RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE;
            case MEDIA_NEXT -> RemoteKeyCode.KEYCODE_MEDIA_NEXT;
            case MEDIA_PREVIOUS -> RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS;
            case MEDIA_STOP -> RemoteKeyCode.KEYCODE_MEDIA_STOP;
            case REWIND -> RemoteKeyCode.KEYCODE_MEDIA_REWIND;
            case FAST_FORWARD -> RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD;
            case INFO -> RemoteKeyCode.KEYCODE_INFO;
            case SETTINGS -> RemoteKeyCode.KEYCODE_SETTINGS;
            case GUIDE -> RemoteKeyCode.KEYCODE_GUIDE;
        };
    }

    static RemoteDirection direction(KeyPress press) {
        return switch (press) {
            case SHORT -> RemoteDirection.SHORT;
            case START_LONG -> RemoteDirection.START_LONG;
            case END_LONG -> RemoteDirection.END_LONG;
        };
    }
}
