package dev.andre.homecontrol.core.playback;

/**
 * The preference ladder of spec §5.3, most preferred first. The planner sorts strategies by it: a native app (Jellyfin's)
 * before an app link, then a Cast receiver's own app (YouTube's pairing, a workflow), a custom Cast message, a Cast LOAD
 * and a bare stream on the Default Media Receiver, then a media renderer (DLNA, UPnP, Sonos), then the server's own
 * player on a local audio sink (Bluetooth).
 */
public enum Rung {
    NATIVE_APP, APP_LINK, CAST_APP, CAST_MESSAGE, CAST_LOAD, CAST_STREAM, RENDERER, LOCAL_SINK
}
