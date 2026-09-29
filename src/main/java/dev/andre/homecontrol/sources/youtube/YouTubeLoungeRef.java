package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.playback.SourceRef;

/**
 * A video to start on a Cast receiver through the receiver's own remote-control pairing (an
 * unofficial, best-effort interface). Created at play time by a resolver, only for devices whose
 * switch is on.
 */
public record YouTubeLoungeRef(String videoId) implements SourceRef {
    @Override
    public String kindLabel() {
        return "YouTube Cast";
    }

    @Override
    public String unroutableReason() {
        return "this device is not a Cast receiver";
    }
}
