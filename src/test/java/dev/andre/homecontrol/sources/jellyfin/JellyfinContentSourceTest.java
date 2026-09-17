package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class JellyfinContentSourceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20));
    private final JellyfinSetupService setup = mock(JellyfinSetupService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JellyfinContentSource source =
            new JellyfinContentSource(client, setup, new JellyfinProperties(true, 2, 5, 20), clock);
    private FakeJellyfinServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    private void connected() throws IOException {
        fake = new FakeJellyfinServer();
        given(setup.connection()).willReturn(Optional.of(
                new JellyfinConnection(fake.url(), "tok", "hc-test-device", FakeJellyfinServer.USER_ID)));
    }

    @Test
    void offersNoRailsUntilConnected() {
        given(setup.connection()).willReturn(Optional.empty());

        assertThat(source.available()).isFalse();
        assertThat(source.rails()).isEmpty();
    }

    @Test
    void offersThreeRailsInOrder() throws IOException {
        connected();

        List<RailDescriptor> rails = source.rails();

        assertThat(rails).extracting(RailDescriptor::id).containsExactly("resume", "next-up", "latest");
        assertThat(rails).extracting(RailDescriptor::title)
                .containsExactly("Continue watching", "Next up", "Latest in library");
    }

    @Test
    void continueWatchingUsesTheResumeQuery() throws IOException {
        connected();
        fake.respond("GET", "/UserItems/Resume", 200, "resume.json");

        Rail rail = source.rail("resume");

        assertThat(rail.items()).hasSize(2);
        assertThat(rail.fetchedAt()).isEqualTo(NOW);
        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/UserItems/Resume");
        assertThat(recorded.query()).isEqualTo(Map.of(
                "userId", FakeJellyfinServer.USER_ID,
                "limit", "20",
                "mediaTypes", "Video",
                "enableUserData", "true",
                "enableImageTypes", "Primary,Thumb,Backdrop",
                "imageTypeLimit", "1"));
        assertThat(recorded.header("authorization")).contains("Token=\"tok\"");
    }

    @Test
    void nextUpLeavesResumableEpisodesToContinueWatching() throws IOException {
        connected();
        fake.respond("GET", "/Shows/NextUp", 200, "next-up.json");

        source.rail("next-up");

        assertThat(fake.last("GET", "/Shows/NextUp").query()).containsEntry("enableResumable", "false");
    }

    @Test
    void latestAsksForDirectlyPlayableItems() throws IOException {
        connected();
        fake.respond("GET", "/Items/Latest", 200, "latest.json");

        Rail rail = source.rail("latest");

        assertThat(rail.items()).hasSize(2);
        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/Items/Latest");
        assertThat(recorded.query()).containsEntry("includeItemTypes", "Movie,Episode")
                .containsEntry("groupItems", "false");
    }

    @Test
    void anUnknownRailIsRejected() throws IOException {
        connected();

        assertThatThrownBy(() -> source.rail("ghost")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsOneItem() throws IOException {
        connected();
        fake.respond("GET", "/Items/b1c2d3e4f5061728394a5b6c7d8e9f01", 200, "item-movie.json");
        fake.respondJson("GET", "/Items/e5d4c3b2a1f04938271605b4c3d2e1f0", 404, "{}");

        Optional<ContentItem> present = source.item("b1c2d3e4f5061728394a5b6c7d8e9f01");
        assertThat(present).isPresent();
        assertThat(present.orElseThrow().title()).isEqualTo("Big Buck Bunny");
        assertThat(fake.last("GET", "/Items/b1c2d3e4f5061728394a5b6c7d8e9f01").query())
                .containsEntry("userId", FakeJellyfinServer.USER_ID);

        assertThat(source.item("e5d4c3b2a1f04938271605b4c3d2e1f0")).isEmpty();

        assertThat(source.item("../x")).isEmpty();
        assertThat(fake.requests("GET", "/Items/../x")).isEmpty();
    }

    @Test
    void upstreamFailuresAreContentSourceExceptions() throws IOException {
        connected();
        fake.respondJson("GET", "/UserItems/Resume", 500, "{}");

        assertThatThrownBy(() -> source.rail("resume"))
                .isInstanceOf(ContentSourceException.class)
                .hasMessageContaining("answered HTTP 500");
    }
}
