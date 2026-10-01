package dev.andre.homecontrol.core.playback;

/**
 * A route a source's own {@link RouteExecutor} runs, found by its {@link #key()}: a content service's own playback
 * API. Sources define these routes in their modules; anything that commands a device still goes through
 * {@code DeviceCommands} (see ADR 0004).
 */
public non-sealed interface DelegatedRoute extends Route {

    /** The source's display name, for "<device>: <source> is switched off on this server". */
    String source();
}
