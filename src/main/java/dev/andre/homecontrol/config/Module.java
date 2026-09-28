package dev.andre.homecontrol.config;

import java.util.Optional;

/**
 * Every device adapter and content source that can be switched off, each with {@code home-control.<segment>.enabled}
 * and its default. The one place that lists them: {@link ConditionalOnModule} reads it, and so do the tests that
 * switch every module off.
 */
public enum Module {
    ANDROIDTV("androidtv", true, null),
    CAST("cast", true, null),
    WEBOS("webos", true, null),
    TIZEN("tizen", true, null),
    UPNP("upnp", true, null),
    SONOS("sonos", true, null),
    BLUETOOTH("bluetooth", false, null),
    JELLYFIN("jellyfin", true, null),
    YOUTUBE("youtube", true, null),
    TMDB("tmdb", true, null),
    PINNED("pinned", true, null),
    SPORTS("sports", true, null),
    THESPORTSDB("sports.thesportsdb", true, SPORTS),
    WORKFLOWS("workflows", true, null);

    private final String segment;
    private final boolean enabledByDefault;
    private final Module parent;

    Module(String segment, boolean enabledByDefault, Module parent) {
        this.segment = segment;
        this.enabledByDefault = enabledByDefault;
        this.parent = parent;
    }

    public String segment() {
        return segment;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /** A module that only runs inside another, such as TheSportsDB inside sports. */
    public Optional<Module> parent() {
        return Optional.ofNullable(parent);
    }

    public String property() {
        return "home-control." + segment + ".enabled";
    }
}
