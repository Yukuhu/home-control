package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every fixture under {@code /fixtures/jellyfin} is checked against the shapes the real code
 * relies on: valid JSON, well-formed mapped {@link ContentItem}s with no credential ever
 * embedded, the documented {@code Sessions} shape and {@link JellyfinStreams}'s direct-play
 * decision. A failure here means a fixture drifted from what a task's production code expects,
 * not a bug in the fixture-authoring task alone.
 */
class JellyfinFixtureContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Path fixturesDir() throws Exception {
        return Path.of(JellyfinFixtureContractTest.class.getResource("/fixtures/jellyfin").toURI());
    }

    private static JsonNode fixture(String name) {
        return MAPPER.readTree(FakeJellyfinServer.fixture(name));
    }

    @Test
    void everyFixtureIsValidJson() throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.list(fixturesDir())) {
            files = paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        }

        assertThat(files).hasSizeGreaterThanOrEqualTo(15);
        for (Path file : files) {
            String content = Files.readString(file);
            assertThat(MAPPER.readTree(content)).as("fixture " + file.getFileName()).isNotNull();
        }
    }

    /** {@code fixture:field} — field is the array property to map, empty for a root array. */
    @ParameterizedTest
    @ValueSource(strings = {"resume.json:Items", "next-up.json:Items", "latest.json:", "search.json:Items"})
    void railAndSearchFixturesProduceWellFormedItems(String spec) {
        int colon = spec.indexOf(':');
        String fixtureName = spec.substring(0, colon);
        String field = spec.substring(colon + 1);
        JsonNode root = fixture(fixtureName);
        JsonNode array = field.isEmpty() ? root : root.path(field);

        List<ContentItem> items = JellyfinItemMapper.toItems(array);

        assertThat(items).as("fixture " + fixtureName).isNotEmpty();
        for (ContentItem item : items) {
            assertThat(item.id()).matches("[0-9a-f]{32}");
            assertThat(item.sourceId()).isEqualTo(JellyfinSettings.SOURCE_ID);
            assertThat(item.title()).isNotBlank();
            if (item.progress() != null) {
                assertThat(item.progress()).isBetween(0.0, 1.0);
            }
            if (item.artwork() != null) {
                assertThat(item.artwork().toString())
                        .startsWith(JellyfinItemMapper.IMAGE_PATH)
                        .contains("?tag=");
            }
            assertThat(item.playables()).hasSize(1);
            PlayableRef.JellyfinItem playable = (PlayableRef.JellyfinItem) item.playables().getFirst();
            assertThat(playable.serverId()).isEqualTo(FakeJellyfinServer.SERVER_ID);
            assertThat(playable.itemId()).isEqualTo(item.id());
            assertThat(playable.resumeTicks()).isGreaterThanOrEqualTo(0L);
        }
    }

    /**
     * None of these fixtures actually carries an {@code AccessToken} or {@code ApiKey} field
     * (Jellyfin never puts one on a {@code BaseItemDto}), so a plain "the mapped item doesn't
     * contain the fixture's token" assertion would pass even if the mapper copied every field of
     * the source JSON verbatim. This test injects both fields onto each fixture item before
     * mapping, so it fails if {@link JellyfinItemMapper} is ever changed to copy unknown/extra
     * fields through instead of building {@link ContentItem} from a fixed, known field list.
     */
    @Test
    void noMappedItemCarriesACredential() {
        String injectedAccessToken = "leaked-access-token-c0ffee00";
        String injectedApiKey = "leaked-api-key-babe1234";
        List<String> fixtures = List.of("resume.json:Items", "next-up.json:Items", "latest.json:", "search.json:Items");
        for (String spec : fixtures) {
            int colon = spec.indexOf(':');
            JsonNode root = fixture(spec.substring(0, colon));
            String field = spec.substring(colon + 1);
            JsonNode array = field.isEmpty() ? root : root.path(field);
            for (JsonNode node : array) {
                ObjectNode item = (ObjectNode) node;
                item.put("AccessToken", injectedAccessToken);
                item.put("ApiKey", injectedApiKey);
            }
            for (ContentItem item : JellyfinItemMapper.toItems(array)) {
                assertThat(item.toString())
                        .as("fixture " + spec)
                        .doesNotContain(injectedAccessToken)
                        .doesNotContain(injectedApiKey)
                        .doesNotContain(FakeJellyfinServer.ACCESS_TOKEN)
                        .doesNotContain("ApiKey");
            }
        }
    }

    @Test
    void sessionFixtureMatchesTheDocumentedShape() {
        JsonNode sessions = fixture("sessions.json");

        assertThat(sessions.isArray()).isTrue();
        for (JsonNode session : sessions) {
            assertThat(session.has("Id")).as("Id").isTrue();
            assertThat(session.has("DeviceId")).as("DeviceId").isTrue();
            assertThat(session.has("DeviceName")).as("DeviceName").isTrue();
            assertThat(session.has("Client")).as("Client").isTrue();
            assertThat(session.has("RemoteEndPoint")).as("RemoteEndPoint").isTrue();
            assertThat(session.has("LastActivityDate")).as("LastActivityDate").isTrue();
            assertThat(session.has("SupportsMediaControl")).as("SupportsMediaControl").isTrue();
        }
    }

    @Test
    void playbackInfoFixturesDecideDirectPlay() throws IOException {
        URI deviceServerUrl = URI.create("http://192.168.1.20:8096");
        JsonNode videoItem = MAPPER.readTree("""
                {"Id":"3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b","MediaType":"Video"}
                """);
        JsonNode audioItem = MAPPER.readTree("""
                {"Id":"c0ffee00c0ffee00c0ffee00c0ffee01","MediaType":"Audio"}
                """);

        assertThat(JellyfinStreams.fromPlaybackInfo(deviceServerUrl, "tok", videoItem, fixture("playback-info-direct.json")))
                .isPresent();
        assertThat(JellyfinStreams.fromPlaybackInfo(deviceServerUrl, "tok", audioItem, fixture("playback-info-audio.json")))
                .isPresent();
        assertThat(JellyfinStreams.fromPlaybackInfo(deviceServerUrl, "tok", videoItem, fixture("playback-info-transcode-only.json")))
                .isEmpty();
    }
}
