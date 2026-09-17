package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JellyfinItemMapperTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode fixture(String name) {
        return MAPPER.readTree(FakeJellyfinServer.fixture(name));
    }

    @Test
    void mapsAResumableEpisode() {
        ContentItem item = JellyfinItemMapper.toItems(fixture("resume.json").path("Items")).getFirst();

        assertThat(item.id()).isEqualTo("3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b");
        assertThat(item.sourceId()).isEqualTo("jellyfin");
        assertThat(item.kind()).isEqualTo(ContentKind.EPISODE);
        assertThat(item.title()).isEqualTo("Northern Lights");
        assertThat(item.subtitle()).isEqualTo("S2:E5 · The Long Night");
        assertThat(item.artwork()).isEqualTo(URI.create("/sources/jellyfin/images/3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b/Primary?tag=1a2b3c4d5e6f"));
        assertThat(item.progress()).isEqualTo(0.425);
        assertThat(item.playables()).containsExactly(new PlayableRef.JellyfinItem(
                "4e1a2b3c4d5e4f60718293a4b5c6d7e8", "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b", 6_120_000_000L));
    }

    @Test
    void mapsAMovieWithItsYearAndComputesProgressFromTicks() {
        ContentItem item = JellyfinItemMapper.toItems(fixture("resume.json").path("Items")).get(1);

        assertThat(item.kind()).isEqualTo(ContentKind.MOVIE);
        assertThat(item.title()).isEqualTo("Big Buck Bunny");
        assertThat(item.subtitle()).isEqualTo("2008");
        assertThat(item.progress()).isEqualTo(0.25);
    }

    @Test
    void fallsBackToTheSeriesPosterAndHasNoProgressWhenUnstarted() {
        ContentItem item = JellyfinItemMapper.toItems(fixture("next-up.json").path("Items")).getFirst();

        assertThat(item.artwork()).isEqualTo(URI.create("/sources/jellyfin/images/7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29/Primary?tag=b8a7c6d5e4f3"));
        assertThat(item.progress()).isNull();
        assertThat(item.playables()).singleElement().extracting(ref -> ((PlayableRef.JellyfinItem) ref).resumeTicks()).isEqualTo(0L);
    }

    @Test
    void skipsFoldersAndPlayedItemsHaveNoProgress() {
        List<ContentItem> latest = JellyfinItemMapper.toItems(fixture("latest.json"));

        assertThat(latest).extracting(ContentItem::title).containsExactly("Sintel", "Harbour Town");
        assertThat(latest.get(1).subtitle()).isEqualTo("S1:E1 · Pilot");
        assertThat(latest.get(1).progress()).isNull();
    }

    @Test
    void aTrackShowsItsArtistsAndAlbumArt() {
        JsonNode track = MAPPER.readTree("""
                {"Id":"c0ffee00c0ffee00c0ffee00c0ffee01","ServerId":"s","Name":"Bunny Song","Type":"Audio",
                 "MediaType":"Audio","Artists":["The Rabbits","Hare"],
                 "AlbumId":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","AlbumPrimaryImageTag":"77aa","ImageTags":{}}
                """);

        ContentItem item = JellyfinItemMapper.toItem(track).orElseThrow();

        assertThat(item.kind()).isEqualTo(ContentKind.TRACK);
        assertThat(item.title()).isEqualTo("Bunny Song");
        assertThat(item.subtitle()).isEqualTo("The Rabbits, Hare");
        assertThat(item.artwork()).isEqualTo(URI.create("/sources/jellyfin/images/a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1/Primary?tag=77aa"));
    }

    @Test
    void anItemWithoutIdIsSkipped() {
        JsonNode noId = MAPPER.readTree("{\"Name\":\"Ghost\",\"Type\":\"Movie\"}");

        Optional<ContentItem> item = JellyfinItemMapper.toItem(noId);

        assertThat(item).isEmpty();
    }
}
