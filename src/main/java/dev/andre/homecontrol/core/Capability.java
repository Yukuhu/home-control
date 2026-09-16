package dev.andre.homecontrol.core;

/** What a device can do. The planner matches an item's playable references against these (spec §5.1). */
public enum Capability {
    REMOTE_KEYS, POWER, VOLUME, APP_LINK, CAST_RECEIVER, MEDIA_RENDERER, JELLYFIN_CLIENT, LOCAL_AUDIO_SINK
}
