package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.IOException;

public class MpvNotInstalledException extends IOException {
    public MpvNotInstalledException(String mpvPath, IOException cause) {
        super("mpv was not found at \"" + mpvPath + "\"", cause);
    }
}
