package dev.andre.homecontrol.core;

import java.time.Instant;

/** Last known state of one device. {@code nowPlaying} is null when nothing plays or the adapter cannot tell. */
public record DeviceState(DeviceStatus status, boolean powerOn, String currentApp,
                          int volumeLevel, int volumeMax, boolean muted, Instant updatedAt, NowPlaying nowPlaying) {

    /** For adapters that cannot report media, and every call site that predates it. */
    public DeviceState(DeviceStatus status, boolean powerOn, String currentApp,
                       int volumeLevel, int volumeMax, boolean muted, Instant updatedAt) {
        this(status, powerOn, currentApp, volumeLevel, volumeMax, muted, updatedAt, null);
    }

    public static DeviceState initial() {
        return new DeviceState(DeviceStatus.DISCONNECTED, false, null, 0, 0, false, Instant.now());
    }

    public static DeviceState unpaired() {
        return new DeviceState(DeviceStatus.UNPAIRED, false, null, 0, 0, false, Instant.now());
    }

    public boolean connected() {
        return status == DeviceStatus.CONNECTED;
    }

    public DeviceState withStatus(DeviceStatus newStatus) {
        return new DeviceState(newStatus, powerOn, currentApp, volumeLevel, volumeMax, muted, Instant.now(), nowPlaying);
    }

    public DeviceState withPower(boolean on) {
        return new DeviceState(status, on, currentApp, volumeLevel, volumeMax, muted, Instant.now(), nowPlaying);
    }

    public DeviceState withCurrentApp(String appPackage) {
        return new DeviceState(status, powerOn, appPackage, volumeLevel, volumeMax, muted, Instant.now(), nowPlaying);
    }

    public DeviceState withVolume(int level, int max, boolean isMuted) {
        return new DeviceState(status, powerOn, currentApp, level, max, isMuted, Instant.now(), nowPlaying);
    }

    public DeviceState withNowPlaying(NowPlaying playing) {
        return new DeviceState(status, powerOn, currentApp, volumeLevel, volumeMax, muted, Instant.now(), playing);
    }
}
