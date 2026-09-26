package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.nio.file.Path;
import java.util.Locale;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.*;

/** Turns D-Bus/BlueZ errors into a failure kind and a sentence that names the fix. */
public final class BluezFailures {

    private static final String DOCS = "see docs/bluetooth-speakers.md";

    private BluezFailures() {
    }

    public static BluezFailure classify(String errorName, String message) {
        String text = ((errorName == null ? "" : errorName) + " " + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        // dbus-java reads a machine id before it even opens the socket: "MachineId file can not be found" / "is empty".
        if (has(text, "machineid")) {
            return NO_MACHINE_ID;
        }
        if (has(text, "serviceunknown", "namehasnoowner", "was not provided by any")) {
            return BLUEZ_NOT_RUNNING;
        }
        if (has(text, "accessdenied", "failed to auth", "rejected send message")) {
            return ACCESS_DENIED;
        }
        if (has(text, "profile-unavailable", "protocol not available")) {
            return NO_AUDIO_PROFILE;
        }
        if (has(text, "authenticationfailed", "authenticationrejected", "authenticationcanceled",
                "authenticationtimeout", "authentication failed", "authentication rejected")) {
            return PAIRING_REJECTED;
        }
        if (has(text, "alreadyexists", "alreadyconnected", "notconnected", "already exists", "already connected")) {
            return ALREADY_DONE;
        }
        if (has(text, "inprogress", "in progress", "busy")) {
            return BUSY;
        }
        if (has(text, "notready", "not ready", "rfkill")) {
            return ADAPTER_OFF;
        }
        if (has(text, "doesnotexist", "does not exist", "unknownobject", "doesn't exist")) {
            return NOT_FOUND;
        }
        if (has(text, "connectionattemptfailed", "connectfailed", "notavailable", "page-timeout", "page timeout",
                "host is down", "br-connection")) {
            return UNREACHABLE;
        }
        if (has(text, "noreply", "timeout", "timed out")) {
            return TIMEOUT;
        }
        return FAILED;
    }

    public static String message(BluezFailure failure, String detail) {
        String why = detail == null || detail.isBlank() ? "" : " (" + detail.strip() + ")";
        return switch (failure) {
            case NO_DBUS_SOCKET -> "No D-Bus system socket" + why + ". Mount /run/dbus into the container, " + DOCS + ".";
            case NO_MACHINE_ID -> "The container has no D-Bus machine id" + why
                    + ". Mount the host's machine id into the container: /etc/machine-id:/etc/machine-id:ro, " + DOCS + ".";
            case ACCESS_DENIED -> "The host's D-Bus refused this container" + why + ". Run the container as root; "
                    + "with AppArmor add security_opt apparmor:unconfined, " + DOCS + ".";
            case BLUEZ_NOT_RUNNING -> "BlueZ is not running on the host" + why
                    + ". Install bluez and run: sudo systemctl enable --now bluetooth";
            case NO_ADAPTER -> "No Bluetooth adapter found on the host" + why
                    + ". Check bluetoothctl list, and run rfkill unblock bluetooth.";
            case ADAPTER_OFF -> "The Bluetooth adapter is off or blocked" + why + ". Run rfkill unblock bluetooth on the host.";
            case NO_AUDIO_PROFILE -> "The host has no Bluetooth audio service for this speaker" + why
                    + ". Start PipeWire (with WirePlumber) or PulseAudio with Bluetooth support for the audio user, " + DOCS + ".";
            case NOT_FOUND -> "The host's Bluetooth adapter does not know this device" + why
                    + ". Put the speaker into pairing mode and scan again.";
            case PAIRING_REJECTED -> "The speaker refused pairing" + why + ". Put it into pairing mode and try again.";
            case UNREACHABLE -> "The speaker did not answer" + why + ". Switch it on, bring it closer and try again.";
            case BUSY -> "The Bluetooth adapter is busy" + why + ". Try again in a few seconds.";
            case ALREADY_DONE -> "Nothing to do" + why + ".";
            case TIMEOUT -> "BlueZ did not answer in time" + why + ".";
            case FAILED -> "Bluetooth operation failed" + why + ".";
        };
    }

    public static String noSocket(Path socket) {
        return message(NO_DBUS_SOCKET, "nothing at " + socket);
    }

    private static boolean has(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
