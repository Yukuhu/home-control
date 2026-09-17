package dev.andre.homecontrol.playback;

/** {@code appBefore}/{@code appAfter} are whatever the device reports as current app, or null. */
public record DeepLinkTestResult(Outcome outcome, String appBefore, String appAfter, String message) {

    public enum Outcome { APP_CHANGED, NO_CHANGE, NOT_OBSERVABLE, FAILED }
}
