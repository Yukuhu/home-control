package dev.andre.homecontrol.adapters.webos;

/** The SSAP endpoints this adapter uses (aiowebostv {@code endpoints.py}). Constants so they can be switch labels. */
final class SsapUris {

    static final String POINTER_INPUT_SOCKET = "ssap://com.webos.service.networkinput/getPointerInputSocket";
    static final String FOREGROUND_APP = "ssap://com.webos.applicationManager/getForegroundAppInfo";
    static final String GET_VOLUME = "ssap://audio/getVolume";
    static final String SET_VOLUME = "ssap://audio/setVolume";
    static final String VOLUME_UP = "ssap://audio/volumeUp";
    static final String VOLUME_DOWN = "ssap://audio/volumeDown";
    static final String SET_MUTE = "ssap://audio/setMute";
    static final String POWER_STATE = "ssap://com.webos.service.tvpower/power/getPowerState";
    static final String TURN_OFF = "ssap://system/turnOff";
    static final String LAUNCH = "ssap://system.launcher/launch";
    static final String OPEN = "ssap://system.launcher/open";
    static final String EXTERNAL_INPUTS = "ssap://tv/getExternalInputList";
    static final String SWITCH_INPUT = "ssap://tv/switchInput";
    static final String MEDIA_STOP = "ssap://media.controls/stop";
    static final String CONNECTION_INFO = "ssap://com.webos.service.connectionmanager/getinfo";
    static final String SYSTEM_INFO = "ssap://system/getSystemInfo";

    private SsapUris() {
    }
}
