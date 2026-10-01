package dev.andre.homecontrol.adapters.support;

import java.util.concurrent.atomic.AtomicBoolean;

/** The play/pause key of a device that takes separate play and pause commands: presses alternate, pause first. */
public final class PlayPauseToggle {

    private final AtomicBoolean playNext = new AtomicBoolean();

    /** Whether this press sends play; otherwise it sends pause. One atomic flip, so concurrent presses alternate too. */
    public boolean playNext() {
        boolean play;
        do {
            play = playNext.get();
        } while (!playNext.compareAndSet(play, !play));
        return play;
    }
}
