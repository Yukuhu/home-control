package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.playback.ContentItem;

import java.util.List;

/** One query answered by every enabled searchable source: what each found, and who failed to answer. */
public record SearchOutcome(String query, List<Hits> hits, List<Failure> failures) {

    public SearchOutcome {
        hits = List.copyOf(hits);
        failures = List.copyOf(failures);
    }

    /** A source's results, in content-source order; empty when it answered with nothing. */
    public record Hits(ContentSource source, List<ContentItem> items) {
        public Hits {
            items = List.copyOf(items);
        }
    }

    /** A source that could not answer in time, or at all. */
    public record Failure(ContentSource source, String message) {
    }
}
