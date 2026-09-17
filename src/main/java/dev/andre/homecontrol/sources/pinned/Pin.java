package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** A user-pinned shortcut to a web/app link, shown in the Pinned rail. */
public record Pin(String id, URI url, String service, String title, String subtitle, URI artwork,
                  ContentKind kind, String upgradeOf, Instant createdAt) {

    public Pin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
