package dev.andre.homecontrol.adapters.tizen;

/** What an app link becomes on a Samsung TV. */
sealed interface TizenLaunch {

    /** DIAL: {@code POST /ws/apps/<app>} with {@code body}. */
    record Dial(String app, String body) implements TizenLaunch {
    }

    /** {@code ed.apps.launch} of an installed app, without content. */
    record App(String appId, String name, String actionType) implements TizenLaunch {
    }

    record Unsupported(String reason) implements TizenLaunch {
    }
}
