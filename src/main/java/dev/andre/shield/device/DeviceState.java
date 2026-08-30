package dev.andre.shield.device;

import java.time.Instant;

public record DeviceState(DeviceStatus status, boolean powerOn, String currentApp,
                          int volumeLevel, int volumeMax, boolean muted, Instant updatedAt) {

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
        return new DeviceState(newStatus, powerOn, currentApp, volumeLevel, volumeMax, muted, Instant.now());
    }

    public DeviceState withPower(boolean on) {
        return new DeviceState(status, on, currentApp, volumeLevel, volumeMax, muted, Instant.now());
    }

    public DeviceState withCurrentApp(String appPackage) {
        return new DeviceState(status, powerOn, appPackage, volumeLevel, volumeMax, muted, Instant.now());
    }

    public DeviceState withVolume(int level, int max, boolean isMuted) {
        return new DeviceState(status, powerOn, currentApp, level, max, isMuted, Instant.now());
    }
}
