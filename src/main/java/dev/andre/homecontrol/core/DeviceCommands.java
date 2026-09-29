package dev.andre.homecontrol.core;

import java.util.Map;

/** Sends actions to registered devices. Commands are ephemeral: one that cannot be carried out now fails now. */
public interface DeviceCommands {

    /**
     * Tries the device's adapters that declare the needed capability, in order. An adapter that could not even send —
     * unsupported, offline, or declaring the capability without a live handle (not yet connected, or a failed connect)
     * — hands over to the next; an adapter whose device answered "no" ({@link ActionFailedException}) ends it. If
     * nobody could send, the first offline reason wins over the last unsupported one; only a capability none of the
     * device's adapters declare is plainly unsupported. An unknown id throws {@link DeviceNotFoundException}.
     */
    void execute(String id, Action action);

    /**
     * Like {@link #execute}, for a question with an answer: only adapters declaring {@link Capability#CAST_RECEIVER}
     * are asked, with the same fall-through (offline or unsupported hands over; a refusal or no answer,
     * {@link ActionFailedException}, ends it).
     */
    Map<String, Object> query(String id, CastAppQuery query);
}
