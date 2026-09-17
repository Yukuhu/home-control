package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeVideoMapperTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode fixture(String name) {
        return MAPPER.readTree(FakeGoogleServer.fixture(name));
    }

    @Test
    void mapsUploads() {
        List<YouTubeVideo> videos = YouTubeVideoMapper.playlistItems(fixture("playlist-items-uploads-kurzgesagt.json"));

        assertThat(videos).hasSize(2);
        assertThat(videos.get(0)).isEqualTo(new YouTubeVideo("Kz1aT5nM3pQ",
                "What If the Moon Turned Into a Black Hole?", "Kurzgesagt – In a Nutshell",
                Instant.parse("2026-09-15T14:00:12Z")));
    }

    @Test
    void skipsUnavailableVideos() {
        List<YouTubeVideo> videos = YouTubeVideoMapper.playlistItems(fixture("playlist-items-uploads-blender.json"));

        assertThat(videos).hasSize(1);
        assertThat(videos.getFirst().id()).isEqualTo("aqz-KE-bpKQ");
        assertThat(videos.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-09-10T12:00:00Z"));
    }

    @Test
    void aMalformedPrimaryPublishedDateFallsBackToSnippet() {
        JsonNode item = MAPPER.readTree("""
                {
                  "snippet": { "title": "Something", "videoOwnerChannelTitle": "Someone", "videoOwnerChannelId": "UC1",
                               "publishedAt": "2026-09-15T14:00:12Z",
                               "resourceId": { "videoId": "Kz1aT5nM3pQ" } },
                  "contentDetails": { "videoPublishedAt": "not-a-real-timestamp" }
                }
                """);

        Optional<YouTubeVideo> video = YouTubeVideoMapper.fromPlaylistItem(item);

        assertThat(video).isPresent();
        assertThat(video.get().publishedAt()).isEqualTo(Instant.parse("2026-09-15T14:00:12Z"));
    }

    @Test
    void rejectsBadIds() {
        JsonNode item = MAPPER.readTree("""
                {
                  "snippet": { "title": "Something", "videoOwnerChannelTitle": "Someone", "videoOwnerChannelId": "UC1",
                               "resourceId": { "videoId": "short" } },
                  "contentDetails": {}
                }
                """);

        assertThat(YouTubeVideoMapper.fromPlaylistItem(item)).isEmpty();
    }

    @Test
    void toItemBuildsTheAppLinkAndThumbnail() {
        List<YouTubeVideo> videos = YouTubeVideoMapper.playlistItems(fixture("playlist-items-uploads-blender.json"));
        YouTubeVideo video = videos.getFirst();

        ContentItem item = video.toItem();

        assertThat(item.sourceId()).isEqualTo("youtube");
        assertThat(item.kind()).isEqualTo(ContentKind.VIDEO);
        assertThat(item.subtitle()).isEqualTo("Blender");
        assertThat(item.artwork()).isEqualTo(URI.create("/sources/youtube/thumbnails/aqz-KE-bpKQ"));
        assertThat(item.playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), "youtube"));
        assertThat(item.progress()).isNull();
    }

    @Test
    void mapsAVideoResource() {
        JsonNode response = fixture("videos-by-id.json");

        Optional<YouTubeVideo> video = YouTubeVideoMapper.fromVideo(response.path("items").path(0));

        assertThat(video).contains(new YouTubeVideo("Wq9Ze2Lr5tA", "Blender 5.0 Reveal", "Blender",
                Instant.parse("2026-08-30T16:45:00Z")));
    }

    @Test
    void mapsASearchResultAndUnescapesItsText() {
        JsonNode items = fixture("search-videos.json").path("items");

        Optional<YouTubeVideo> video = YouTubeVideoMapper.fromSearchResult(items.path(1));

        assertThat(video).contains(new YouTubeVideo("Hh7Lq2Wv9sE", "Bunnies & Black Holes: \"Why\" It's Not Fine",
                "Kurzgesagt &ndash; In a Nutshell", Instant.parse("2026-09-01T14:00:00Z")));
    }

    @Test
    void dropsUpcomingSearchResults() {
        JsonNode items = fixture("search-videos.json").path("items");

        assertThat(YouTubeVideoMapper.fromSearchResult(items.path(2))).isEmpty();
    }
}
