package dev.andre.shield.protocol;

/** Device-initiated events. Every method is a no-op by default. */
public interface RemoteListener {

    default void onPower(boolean on) {
    }

    default void onCurrentApp(String appPackage) {
    }

    default void onVolume(int level, int max, boolean muted) {
    }

    default void onDisconnected(DisconnectCause cause) {
    }
}
