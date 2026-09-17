package dev.andre.homecontrol.core;

public sealed interface PromptPairingResult {

    record Paired(Device device) implements PromptPairingResult {
    }

    /** The user said no on the device. */
    record Declined(String reason) implements PromptPairingResult {
    }

    /** Unreachable, timed out, or the device answered something unexpected. */
    record Failed(String reason) implements PromptPairingResult {
    }
}
