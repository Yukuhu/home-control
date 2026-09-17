package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentSourcesTest {

    private static ContentSource source(String id, boolean searchable, boolean available) {
        return new ContentSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public List<RailDescriptor> rails() {
                return List.of();
            }

            @Override
            public Rail rail(String railId) {
                return new Rail(new RailDescriptor(id, railId, railId), List.of(), Instant.EPOCH);
            }

            @Override
            public Optional<ContentItem> item(String itemId) {
                return Optional.empty();
            }

            @Override
            public boolean searchable() {
                return searchable;
            }
        };
    }

    @Test
    void findsBySourceId() {
        ContentSource jellyfin = source("jellyfin", true, true);
        ContentSource pinned = source("pinned", false, true);
        ContentSources sources = new ContentSources(List.of(jellyfin, pinned));

        assertThat(sources.find("jellyfin")).contains(jellyfin);
        assertThat(sources.find("pinned")).contains(pinned);
        assertThat(sources.find("ghost")).isEmpty();
        assertThat(sources.all()).containsExactly(jellyfin, pinned);
    }

    @Test
    void searchableSkipsSourcesThatCannotSearchOrAreUnavailable() {
        ContentSource jellyfin = source("jellyfin", true, true);
        ContentSource pinned = source("pinned", false, true);
        ContentSource unavailable = source("unavailable", true, false);
        ContentSources sources = new ContentSources(List.of(jellyfin, pinned, unavailable));

        assertThat(sources.searchable()).containsExactly(jellyfin);
    }

    @Test
    void duplicateSourceIdsAreAProgrammingError() {
        ContentSource first = source("jellyfin", true, true);
        ContentSource second = source("jellyfin", false, true);

        assertThatThrownBy(() -> new ContentSources(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class);
    }
}
