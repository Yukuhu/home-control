package dev.andre.homecontrol.core.playback;

/**
 * A route a source's own {@link RouteExecutor} runs, found by its {@link #key()}: a content service's playback API,
 * such as a Jellyfin session or the YouTube Lounge (see ADR 0004).
 */
public non-sealed interface DelegatedRoute extends Route {

    /** The source's display name, for "<device>: <source> is switched off on this server". */
    String source();
}
