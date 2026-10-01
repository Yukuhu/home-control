package dev.andre.homecontrol.core;

/**
 * What a device can do through one adapter. An adapter declares exactly what its sessions carry out, so a command is
 * sent only to adapters that can do it; the planner matches an item's playable references against these (spec §5.1).
 */
public enum Capability {
    /** Takes remote keys, the power key included. */
    REMOTE_KEYS,
    /** Sets and mutes the volume. */
    VOLUME,
    /** Opens a link in whatever app claims it. */
    APP_LINK,
    /** Runs installed Android apps and opens {@code market://} and intent links (the Jellyfin app, VLC). */
    ANDROID_APPS,
    /** Lists its inputs ({@link InputListing}) and switches between them. */
    INPUTS,
    /** Joins and leaves speaker groups ({@link GroupListing}). */
    GROUPING,
    /** Is switched on with a Wake-on-LAN magic packet to the MAC in the adapter's settings. */
    WAKE_ON_LAN,
    /** Runs Cast receiver apps: loads, messages and receiver-app questions. */
    CAST_RECEIVER,
    /** Plays a stream it fetches itself (UPnP, Sonos). */
    MEDIA_RENDERER,
    /** A Jellyfin client session reachable right now; no adapter declares it, the Jellyfin resolver adds it. */
    JELLYFIN_CLIENT,
    /** Plays a stream through the server's own player (Bluetooth). */
    LOCAL_AUDIO_SINK
}
