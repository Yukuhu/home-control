package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailSnapshot;

import java.time.Instant;

/** The `rail` SSE payload: a summary only; the browser fetches the fragment when the version changed. */
public record RailEventView(String sourceId, String railId, String status, long version,
                           Instant fetchedAt, String error, boolean refreshing) {

    public static RailEventView of(RailSnapshot snapshot) {
        return new RailEventView(snapshot.sourceId(), snapshot.railId(), snapshot.status().name(),
                snapshot.version(), snapshot.fetchedAt(), snapshot.error(), snapshot.refreshing());
    }
}
