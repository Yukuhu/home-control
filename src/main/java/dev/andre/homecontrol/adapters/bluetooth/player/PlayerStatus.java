package dev.andre.homecontrol.adapters.bluetooth.player;

public record PlayerStatus(boolean paused, boolean buffering, double positionSeconds, Double durationSeconds,
                           String metadataTitle, int volume, boolean muted) {
}
