package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.core.content.RailDescriptor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RailEventViewTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void summarisesWithoutItems() {
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "resume", "Continue watching");
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.READY, List.of(),
                Instant.parse("2026-09-16T09:00:00Z"), null, false, 7);

        String json = mapper.writeValueAsString(RailEventView.of(snapshot));
        JsonNode node = mapper.readTree(json);

        assertThat(node.propertyNames()).containsExactlyInAnyOrder(
                "sourceId", "railId", "status", "version", "fetchedAt", "error", "refreshing");
        assertThat(node.path("sourceId").asString()).isEqualTo("jellyfin");
        assertThat(node.path("railId").asString()).isEqualTo("resume");
        assertThat(node.path("status").asString()).isEqualTo("READY");
        assertThat(node.path("version").asLong()).isEqualTo(7L);
        assertThat(node.path("refreshing").asBoolean()).isFalse();
        assertThat(json).doesNotContain("Continue watching");
    }
}
