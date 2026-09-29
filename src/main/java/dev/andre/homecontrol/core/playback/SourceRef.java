package dev.andre.homecontrol.core.playback;

/** A reference only one source builds and routes; it lives in that source's module (see ADR 0004). */
public non-sealed interface SourceRef extends PlayableRef {

    /** Why nothing routed this reference, shown when no route exists, e.g. "the Jellyfin app cannot be started". */
    String unroutableReason();
}
