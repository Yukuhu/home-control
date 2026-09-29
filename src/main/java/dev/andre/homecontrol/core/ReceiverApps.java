package dev.andre.homecontrol.core;

import java.util.Map;

/** A connection that can ask a receiver app a question: Cast. Found with {@link DeviceHandle#feature}. */
public interface ReceiverApps {

    /** Asks the receiver app and returns its reply, now or never (same exceptions as {@link DeviceHandle#execute}). */
    Map<String, Object> query(CastAppQuery query);
}
