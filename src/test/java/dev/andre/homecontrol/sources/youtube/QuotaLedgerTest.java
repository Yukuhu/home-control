package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaLedgerTest {

    @TempDir
    Path tempDir;

    private Path file;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("youtube-quota.json");
        clock = new MutableClock(Instant.parse("2026-09-16T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    }

    @Test
    void chargesDocumentedUnits() {
        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);

        ledger.charge(QuotaLedger.Call.SUBSCRIPTIONS_LIST);
        ledger.charge(QuotaLedger.Call.CHANNELS_LIST);
        ledger.charge(QuotaLedger.Call.PLAYLIST_ITEMS_LIST);
        ledger.charge(QuotaLedger.Call.PLAYLIST_ITEMS_LIST);
        ledger.charge(QuotaLedger.Call.PLAYLIST_ITEMS_LIST);
        ledger.charge(QuotaLedger.Call.SEARCH_LIST);

        QuotaLedger.Usage usage = ledger.usage();
        assertThat(usage.units()).isEqualTo(105);
        assertThat(usage.searches()).isEqualTo(1);
        assertThat(usage.calls()).isEqualTo(Map.of("subscriptions.list", 1, "channels.list", 1,
                "playlistItems.list", 3, "search.list", 1));
    }

    @Test
    void persistsAndReloads() throws IOException {
        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);
        ledger.charge(QuotaLedger.Call.SUBSCRIPTIONS_LIST);
        ledger.charge(QuotaLedger.Call.CHANNELS_LIST);

        QuotaLedger reloaded = new QuotaLedger(file, clock, 10000, 20);

        assertThat(reloaded.usage().units()).isEqualTo(ledger.usage().units());
        assertThat(reloaded.usage().calls()).isEqualTo(ledger.usage().calls());

        JsonNode root = JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        assertThat(root.path("version").asInt()).isEqualTo(1);
        assertThat(root.path("day").asString()).isEqualTo("2026-09-16");
        assertThat(root.path("units").asInt()).isEqualTo(2);
        assertThat(root.path("searches").asInt()).isEqualTo(0);
        assertThat(root.path("calls").path("subscriptions.list").asInt()).isEqualTo(1);
        assertThat(root.path("calls").path("channels.list").asInt()).isEqualTo(1);
    }

    @Test
    void theDayIsPacific() {
        clock = new MutableClock(Instant.parse("2026-09-17T06:59:00Z"), ZoneId.of("Europe/Berlin"));
        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);
        ledger.charge(QuotaLedger.Call.SUBSCRIPTIONS_LIST);

        assertThat(ledger.usage().day().toString()).isEqualTo("2026-09-16");
        assertThat(ledger.usage().units()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));

        assertThat(ledger.usage().day().toString()).isEqualTo("2026-09-17");
        assertThat(ledger.usage().units()).isEqualTo(0);
    }

    @Test
    void resetsAtMidnightPacificInLocalTime() {
        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);

        ZonedDateTime resetsAt = ledger.usage().resetsAt();

        assertThat(resetsAt).isEqualTo(ZonedDateTime.parse("2026-09-17T09:00:00+02:00[Europe/Berlin]"));
        assertThat(QuotaLedger.resetPhrase(resetsAt)).isEqualTo("after midnight Pacific time (09:00 here)");
    }

    @Test
    void refusesBeyondTheBudget() {
        QuotaLedger ledger = new QuotaLedger(file, clock, 3, 20);

        ledger.charge(QuotaLedger.Call.SUBSCRIPTIONS_LIST);
        ledger.charge(QuotaLedger.Call.CHANNELS_LIST);
        ledger.charge(QuotaLedger.Call.PLAYLIST_ITEMS_LIST);

        assertThatThrownBy(() -> ledger.charge(QuotaLedger.Call.PLAYLIST_ITEMS_LIST))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
        assertThatThrownBy(() -> ledger.charge(QuotaLedger.Call.PLAYLIST_ITEMS_LIST))
                .hasMessage("YouTube's daily API quota is used up (3 of 3 units). Rails refresh again"
                        + " after midnight Pacific time (09:00 here).");
        assertThat(ledger.usage().units()).isEqualTo(3);
    }

    @Test
    void searchesHaveTheirOwnCap() {
        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 2);
        ledger.charge(QuotaLedger.Call.SEARCH_LIST);
        ledger.charge(QuotaLedger.Call.SEARCH_LIST);

        assertThatThrownBy(() -> ledger.charge(QuotaLedger.Call.SEARCH_LIST))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.SEARCH_LIMIT);
        assertThatThrownBy(() -> ledger.charge(QuotaLedger.Call.SEARCH_LIST))
                .hasMessage("You have used today's 2 YouTube searches. More after midnight Pacific time (09:00 here).");

        QuotaLedger tightBudget = new QuotaLedger(tempDir.resolve("other-quota.json"), clock, 50, 10);
        assertThatThrownBy(() -> tightBudget.charge(QuotaLedger.Call.SEARCH_LIST))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
    }

    @Test
    void markExhaustedFillsTheDay() {
        QuotaLedger ledger = new QuotaLedger(file, clock, 100, 20);

        ledger.markExhausted();

        assertThat(ledger.usage().units()).isEqualTo(100);
        assertThat(ledger.usage().exhausted()).isTrue();
        assertThatThrownBy(() -> ledger.charge(QuotaLedger.Call.SUBSCRIPTIONS_LIST))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.QUOTA_EXHAUSTED);
    }

    @Test
    void aCorruptFileIsSetAsideNotFatal() throws IOException {
        Files.writeString(file, "{nope");

        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);

        assertThat(ledger.usage().units()).isZero();
        try (Stream<Path> siblings = Files.list(tempDir)) {
            assertThat(siblings.anyMatch(p -> p.getFileName().toString().startsWith("youtube-quota.json.corrupt-"))).isTrue();
        }
    }

    @Test
    void anOldDayStartsFresh() throws IOException {
        Files.writeString(file, "{\"version\":1,\"day\":\"2026-09-15\",\"units\":9000,\"searches\":5,\"calls\":{}}");

        QuotaLedger ledger = new QuotaLedger(file, clock, 10000, 20);

        assertThat(ledger.usage().units()).isZero();
        assertThat(ledger.usage().day().toString()).isEqualTo("2026-09-16");
    }
}
