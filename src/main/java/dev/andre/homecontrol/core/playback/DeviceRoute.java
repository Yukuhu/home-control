package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;

/** A route a device adapter carries: its action goes to the device through {@code DeviceCommands}. */
public sealed interface DeviceRoute extends Route
        permits Route.OpenAppLink, Route.Cast, Route.CastMessage, Route.Render, Route.PlayLocally {

    Action action();
}
