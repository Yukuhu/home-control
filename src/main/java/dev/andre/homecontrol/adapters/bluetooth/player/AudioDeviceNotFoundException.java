package dev.andre.homecontrol.adapters.bluetooth.player;

/** No audio output was found for a speaker's MAC address. */
public class AudioDeviceNotFoundException extends Exception {
    public AudioDeviceNotFoundException(String message) {
        super(message);
    }
}
