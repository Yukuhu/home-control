package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

class ContentItemViewTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void carriesTimesAsIsoStrings() {
        Instant start = Instant.parse("2026-09-19T13:30:00Z");
        Instant end = Instant.parse("2026-09-19T15:25:00Z");
        ContentItem item = new ContentItem("id", "sports", ContentKind.LIVE_EVENT, "Title", "Subtitle",
                URI.create("/content/sports/artwork/id"), List.of(), null, start, end);

        ContentItemView view = ContentItemView.of(item);

        assertThat(view.startsAt()).isEqualTo("2026-09-19T13:30:00Z");
        assertThat(view.endsAt()).isEqualTo("2026-09-19T15:25:00Z");

        String json = mapper.writeValueAsString(view);
        JsonNode node = mapper.readTree(json);
        Iterator<String> names = node.propertyNames().iterator();
        List<String> order = new java.util.ArrayList<>();
        names.forEachRemaining(order::add);
        assertThat(order).endsWith("progress", "startsAt", "endsAt");
    }

    @Test
    void anItemWithoutTimesHasNullTimes() {
        ContentItem item = new ContentItem("id", "jellyfin", ContentKind.MOVIE, "Title", null, null, List.of());

        ContentItemView view = ContentItemView.of(item);

        assertThat(view.startsAt()).isNull();
        assertThat(view.endsAt()).isNull();
    }
}
