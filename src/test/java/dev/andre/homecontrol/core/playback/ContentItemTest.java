package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentItemTest {

    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://example.org/a"), "web");

    @Test
    void theSevenArgumentConstructorHasNoProgress() {
        ContentItem item = new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(LINK));

        assertThat(item.progress()).isNull();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void progressIsAFractionBetweenZeroAndOne(double invalid) {
        assertThatThrownBy(() -> new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(), invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroAndOneAreAccepted() {
        assertThat(new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(), 0.0).progress()).isEqualTo(0.0);
        assertThat(new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(), 1.0).progress()).isEqualTo(1.0);
    }

    @Test
    void playablesAreCopied() {
        List<PlayableRef> mutable = new ArrayList<>(List.of(LINK));

        ContentItem item = new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, mutable);
        mutable.add(LINK);

        assertThat(item.playables()).containsExactly(LINK);
    }

    @Test
    void sevenAndEightArgumentConstructorsHaveNoTimes() {
        ContentItem seven = new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(LINK));
        ContentItem eight = new ContentItem("id", "src", ContentKind.VIDEO, "Title", null, null, List.of(LINK), 0.5);

        assertThat(seven.startsAt()).isNull();
        assertThat(seven.endsAt()).isNull();
        assertThat(eight.startsAt()).isNull();
        assertThat(eight.endsAt()).isNull();
        assertThat(eight.progress()).isEqualTo(0.5);
    }

    @Test
    void timesAreValidated() {
        Instant start = Instant.parse("2026-09-19T13:30:00Z");
        Instant beforeStart = start.minusSeconds(1);
        List<PlayableRef> noPlayables = List.of();

        assertThatThrownBy(() -> new ContentItem("id", "src", ContentKind.LIVE_EVENT, "Title", null, null,
                noPlayables, null, null, start))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endsAt needs startsAt");
        assertThatThrownBy(() -> new ContentItem("id", "src", ContentKind.LIVE_EVENT, "Title", null, null,
                noPlayables, null, start, beforeStart))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endsAt must not be before startsAt");

        ContentItem equal = new ContentItem("id", "src", ContentKind.LIVE_EVENT, "Title", null, null,
                List.of(), null, start, start);
        assertThat(equal.endsAt()).isEqualTo(start);
    }

    @Test
    void withPlayablesKeepsTheTimes() {
        Instant start = Instant.parse("2026-09-19T13:30:00Z");
        Instant end = Instant.parse("2026-09-19T15:25:00Z");
        ContentItem item = new ContentItem("id", "src", ContentKind.LIVE_EVENT, "Title", null, null,
                List.of(), null, start, end);

        ContentItem replaced = item.withPlayables(List.of(LINK));

        assertThat(replaced.startsAt()).isEqualTo(start);
        assertThat(replaced.endsAt()).isEqualTo(end);
    }
}
