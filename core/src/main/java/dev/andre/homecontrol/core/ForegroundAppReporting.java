package dev.andre.homecontrol.core;

/** How an adapter learns which app is in front. Declared best first. */
public enum ForegroundAppReporting {
    /** The device pushes every change. */
    LIVE,
    /** The adapter polls, and may only recognise some apps. */
    POLLED,
    /** The adapter cannot tell. */
    NONE
}
