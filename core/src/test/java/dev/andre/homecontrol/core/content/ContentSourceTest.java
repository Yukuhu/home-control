package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentSourceTest {

    /** A source that declares only what every source must: no search, no availability check, no refresh interval. */
    private final ContentSource plain = new ContentSource() {
        @Override
        public String id() {
            return "plain";
        }

        @Override
        public String displayName() {
            return "Plain";
        }

        @Override
        public List<RailDescriptor> rails() {
            return List.of();
        }

        @Override
        public Rail rail(String railId) {
            throw new IllegalArgumentException("No such rail " + railId);
        }

        @Override
        public Optional<ContentItem> item(String itemId) {
            return Optional.empty();
        }
    };

    @Test
    void aSourceIsAvailableUnlessItSaysOtherwise() {
        assertThat(plain.available()).isTrue();
    }

    @Test
    void aSourceThatDoesNotSearchRefusesASearchByName() {
        assertThat(plain.searchable()).isFalse();
        assertThat(plain.searchOnDemand()).isFalse();
        assertThat(plain.searchNote()).isEmpty();
        assertThatThrownBy(() -> plain.search("bunny", 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Plain cannot search");
    }

    @Test
    void railsStayFreshForFifteenMinutesByDefault() {
        assertThat(plain.defaultRefreshInterval()).isEqualTo(Duration.ofMinutes(15));
    }
}
