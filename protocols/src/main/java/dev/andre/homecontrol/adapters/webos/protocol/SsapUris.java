package dev.andre.homecontrol.adapters.webos.protocol;

/** The SSAP endpoints this adapter uses (aiowebostv {@code endpoints.py}). Constants so they can be switch labels. */
public final class SsapUris {

    public static final String POINTER_INPUT_SOCKET = "ssap://com.webos.service.networkinput/getPointerInputSocket";
    public static final String FOREGROUND_APP = "ssap://com.webos.applicationManager/getForegroundAppInfo";
    public static final String GET_VOLUME = "ssap://audio/getVolume";
    public static final String SET_VOLUME = "ssap://audio/setVolume";
    public static final String VOLUME_UP = "ssap://audio/volumeUp";
    public static final String VOLUME_DOWN = "ssap://audio/volumeDown";
    public static final String SET_MUTE = "ssap://audio/setMute";
    public static final String POWER_STATE = "ssap://com.webos.service.tvpower/power/getPowerState";
    public static final String TURN_OFF = "ssap://system/turnOff";
    public static final String LAUNCH = "ssap://system.launcher/launch";
    public static final String OPEN = "ssap://system.launcher/open";
    public static final String EXTERNAL_INPUTS = "ssap://tv/getExternalInputList";
    public static final String SWITCH_INPUT = "ssap://tv/switchInput";
    public static final String MEDIA_STOP = "ssap://media.controls/stop";
    public static final String CONNECTION_INFO = "ssap://com.webos.service.connectionmanager/getinfo";
    public static final String SYSTEM_INFO = "ssap://system/getSystemInfo";

    private SsapUris() {
    }
}
