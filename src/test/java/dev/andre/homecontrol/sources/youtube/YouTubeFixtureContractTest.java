package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every fixture under {@code /fixtures/youtube} pinned to the shape the earlier YouTube tasks and
 * their tests rely on: exactly the deliberate 28-file set from the plan's File Structure Map,
 * valid JSON, Google's own OAuth/list/error envelopes, video ids that match YouTube's own id
 * format, escaped search titles the way the real API sends them, the subscriptions ↔ channels ↔
 * uploads-playlist cross-references {@link SubscriptionsFeed} relies on, and the Lounge fixtures
 * {@link LoungeClient} parses. A failure here means a fixture drifted from what earlier tasks'
 * code and tests expect, not a bug newly introduced by this task.
 */
class YouTubeFixtureContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** The plan's File Structure Map fixture list (2026-09-16, Task 6), verbatim. */
    private static final Set<String> EXPECTED_FIXTURES = Set.of(
            "oauth-device-code.json", "oauth-token-pending.json", "oauth-token-slow-down.json",
            "oauth-token-denied.json", "oauth-token-expired.json", "oauth-token-granted.json",
            "oauth-invalid-client-type.json", "oauth-refresh-granted.json", "oauth-refresh-invalid-grant.json",
            "channels-mine.json", "subscriptions-page-1.json", "subscriptions-page-2.json", "channels-uploads.json",
            "playlist-items-uploads-kurzgesagt.json", "playlist-items-uploads-blender.json",
            "playlist-items-uploads-nasa.json", "playlist-items-empty.json", "playlist-items-playlist.json",
            "playlists-mine.json", "search-videos.json", "videos-by-id.json", "error-quota-exceeded.json",
            "error-api-not-enabled.json", "error-playlist-not-found.json", "error-unauthorized.json",
            "lounge-token-batch.json", "lounge-bind.txt", "cast-mdx-session-status.json");

    private static Path fixturesDir() throws Exception {
        return Path.of(YouTubeFixtureContractTest.class.getResource("/fixtures/youtube").toURI());
    }

    private static JsonNode fixture(String name) {
        return MAPPER.readTree(FakeGoogleServer.fixture(name));
    }

    private static List<String> fixtureNames() throws Exception {
        try (Stream<Path> paths = Files.list(fixturesDir())) {
            return paths.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> jsonFixtureNames() throws Exception {
        return fixtureNames().stream().filter(name -> name.endsWith(".json")).toList();
    }

    @Test
    void theFixtureSetIsDeliberate() throws Exception {
        assertThat(EXPECTED_FIXTURES).hasSize(28);
        Set<String> actual;
        try (Stream<Path> paths = Files.list(fixturesDir())) {
            actual = paths.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_FIXTURES);
    }

    @Test
    void everyJsonFixtureParses() throws Exception {
        List<String> names = jsonFixtureNames();
        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertThat(fixture(name).isObject()).as("fixture " + name + " parses to a JSON object").isTrue();
        }
    }

    @Test
    void oauthFixturesHaveGooglesShape() throws Exception {
        JsonNode deviceCode = fixture("oauth-device-code.json");
        assertThat(deviceCode.path("device_code").asString("")).isNotBlank();
        assertThat(deviceCode.path("user_code").asString("")).isNotBlank();
        assertThat(deviceCode.path("verification_url").asString("")).isNotBlank();
        assertThat(deviceCode.path("expires_in").asInt(0)).isPositive();
        assertThat(deviceCode.path("interval").asInt(0)).isPositive();

        List<String> tokenFixtures = jsonFixtureNames().stream()
                .filter(name -> name.startsWith("oauth-token-") || name.startsWith("oauth-refresh-")
                        || name.startsWith("oauth-invalid-"))
                .toList();
        assertThat(tokenFixtures).isNotEmpty();
        List<String> grantedWithRefreshToken = new ArrayList<>();
        for (String name : tokenFixtures) {
            JsonNode node = fixture(name);
            boolean isSuccess = node.has("access_token");
            boolean isError = node.has("error");
            assertThat(isSuccess ^ isError).as("fixture " + name + " is exactly one of a grant or an error").isTrue();
            if (isSuccess) {
                assertThat(node.path("expires_in").asInt(0)).as(name).isPositive();
                assertThat(node.path("token_type").asString("")).as(name).isEqualTo("Bearer");
                if (node.has("refresh_token") && !node.path("refresh_token").asString("").isBlank()) {
                    grantedWithRefreshToken.add(name);
                }
            } else {
                assertThat(node.path("error").asString("")).as(name).isNotBlank();
                assertThat(node.path("error_description").asString("")).as(name).isNotBlank();
            }
        }
        assertThat(grantedWithRefreshToken).containsExactly("oauth-token-granted.json");
    }

    @Test
    void listResponsesHaveKindItemsAndPageInfo() throws Exception {
        List<String> prefixes = List.of("subscriptions-", "channels-", "playlist-items-", "playlists-", "search-", "videos-");
        List<String> names = jsonFixtureNames().stream()
                .filter(name -> prefixes.stream().anyMatch(name::startsWith))
                .toList();
        assertThat(names).hasSize(12);
        for (String name : names) {
            JsonNode node = fixture(name);
            assertThat(node.path("kind").asString("")).as(name).endsWith("ListResponse");
            assertThat(node.path("items").isArray()).as(name + " items").isTrue();
            int items = node.path("items").size();
            int totalResults = node.path("pageInfo").path("totalResults").asInt(-1);
            assertThat(totalResults).as(name + " pageInfo.totalResults").isGreaterThanOrEqualTo(0);
            if (!name.startsWith("search-")) {
                assertThat(totalResults).as(name + " totalResults >= items.size()").isGreaterThanOrEqualTo(items);
            }
        }
    }

    @Test
    void errorFixturesHaveGooglesErrorShape() throws Exception {
        List<String> names = jsonFixtureNames().stream().filter(name -> name.startsWith("error-")).toList();
        assertThat(names).hasSize(4);
        for (String name : names) {
            JsonNode error = fixture(name).path("error");
            assertThat(error.path("code").asInt(0)).as(name + " error.code").isPositive();
            assertThat(error.path("message").asString("")).as(name + " error.message").isNotBlank();
            assertThat(error.path("errors").path(0).path("reason").asString("")).as(name + " error.errors[0].reason").isNotBlank();
        }
    }

    @Test
    void everyPlaylistItemIsMappableOrDeliberatelyUnavailable() throws Exception {
        List<String> names = jsonFixtureNames().stream().filter(name -> name.startsWith("playlist-items-")).toList();
        assertThat(names).hasSize(5);
        int unavailable = 0;
        for (String name : names) {
            for (JsonNode item : fixture(name).path("items")) {
                if (YouTubeVideoMapper.fromPlaylistItem(item).isPresent()) {
                    continue;
                }
                String title = item.path("snippet").path("title").asString("");
                assertThat(title).as("unmappable item in " + name).isIn("Private video", "Deleted video");
                unavailable++;
            }
        }
        assertThat(unavailable).isEqualTo(1);
    }

    @Test
    void videoIdsAreValid() throws Exception {
        List<String> videoIds = new ArrayList<>();
        for (String name : jsonFixtureNames()) {
            collectVideoIds(fixture(name), videoIds);
        }
        assertThat(videoIds).isNotEmpty();
        for (String id : videoIds) {
            assertThat(id).as("videoId").matches(VIDEO_ID);
        }
    }

    private static void collectVideoIds(JsonNode node, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if ("videoId".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    out.add(entry.getValue().asString(""));
                }
                collectVideoIds(entry.getValue(), out);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectVideoIds(element, out);
            }
        }
    }

    @Test
    void searchTitlesAreEscapedLikeTheApi() {
        JsonNode search = fixture("search-videos.json");
        List<String> titles = new ArrayList<>();
        for (JsonNode item : search.path("items")) {
            titles.add(item.path("snippet").path("title").asString(""));
        }
        assertThat(titles).anyMatch(title -> title.contains("&amp;"))
                .anyMatch(title -> title.contains("&#39;"));
    }

    @Test
    void uploadsPlaylistsBelongToSubscribedChannels() {
        JsonNode uploadsChannels = fixture("channels-uploads.json").path("items");
        Set<String> subscribedChannelIds = new LinkedHashSet<>();
        for (String page : List.of("subscriptions-page-1.json", "subscriptions-page-2.json")) {
            for (JsonNode item : fixture(page).path("items")) {
                subscribedChannelIds.add(item.path("snippet").path("resourceId").path("channelId").asString(""));
            }
        }

        List<JsonNode> playlistItemFixtures = List.of(
                fixture("playlist-items-uploads-kurzgesagt.json"),
                fixture("playlist-items-uploads-blender.json"),
                fixture("playlist-items-uploads-nasa.json"));

        Set<String> uploadsPlaylistIdsFromFixtures = new LinkedHashSet<>();
        for (JsonNode fixtureNode : playlistItemFixtures) {
            Set<String> playlistIdsInFixture = new LinkedHashSet<>();
            for (JsonNode item : fixtureNode.path("items")) {
                playlistIdsInFixture.add(item.path("snippet").path("playlistId").asString(""));
            }
            assertThat(playlistIdsInFixture).as("every item in a playlist-items-uploads-*.json shares one playlistId").hasSize(1);
            uploadsPlaylistIdsFromFixtures.addAll(playlistIdsInFixture);
        }

        for (JsonNode channel : uploadsChannels) {
            String channelId = channel.path("id").asString("");
            assertThat(subscribedChannelIds).as("channels-uploads.json id " + channelId).contains(channelId);
            String uploadsPlaylistId = channel.path("contentDetails").path("relatedPlaylists").path("uploads").asString("");
            assertThat(uploadsPlaylistId).as(channelId + " uploads playlist id").isNotBlank();
            assertThat(uploadsPlaylistIdsFromFixtures).as(channelId + " has a matching playlist-items-uploads-*.json")
                    .contains(uploadsPlaylistId);
        }
    }

    @Test
    void loungeFixturesParse() {
        JsonNode tokenBatch = fixture("lounge-token-batch.json");
        String loungeToken = tokenBatch.path("screens").path(0).path("loungeToken").asString("");
        assertThat(loungeToken).isNotBlank();
        String screenId = tokenBatch.path("screens").path(0).path("screenId").asString("");
        assertThat(screenId).isNotBlank();

        LoungeClient.LoungeSession session = LoungeClient.parseBind(FakeGoogleServer.fixture("lounge-bind.txt"));
        assertThat(session.sid()).isEqualTo("8A3F2E1D0C9B8A77");

        JsonNode mdxStatus = fixture("cast-mdx-session-status.json");
        assertThat(mdxStatus.path("type").asString("")).isEqualTo("mdxSessionStatus");
        assertThat(mdxStatus.path("data").path("screenId").asString("")).isEqualTo(screenId);
    }
}
