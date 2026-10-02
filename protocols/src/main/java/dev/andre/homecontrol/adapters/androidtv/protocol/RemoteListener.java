package dev.andre.homecontrol.adapters.androidtv.protocol;

/** Device-initiated events. Every method is a no-op by default. */
public interface RemoteListener {

    /** The device completed the Remote v2 configure/active exchange. */
    default void onReady() {
        // Consumers that do not track connection readiness need no notification.
    }

    default void onPower(boolean on) {
    }

    default void onCurrentApp(String appPackage) {
    }

    default void onVolume(int level, int max, boolean muted) {
    }

    default void onDisconnected(DisconnectCause cause) {
    }
}
