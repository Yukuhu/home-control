package dev.andre.homecontrol.core;

import java.net.URI;

/** A command for one device. Each action names the capability an adapter must declare to accept it. */
public sealed interface Action {

    Capability requires();

    record PressKey(RemoteKey key) implements Action {
        @Override
        public Capability requires() {
            return Capability.REMOTE_KEYS;
        }
    }

    /** Ask the device to open a URI in whatever app claims it. Optimistic by design (spec §5.3). */
    record OpenAppLink(URI uri) implements Action {
        @Override
        public Capability requires() {
            return Capability.APP_LINK;
        }
    }
}
