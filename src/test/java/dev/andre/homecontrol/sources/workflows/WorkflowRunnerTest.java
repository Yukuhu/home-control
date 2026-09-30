package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowRunnerTest {
    private static final WorkflowProperties PROPERTIES =
            new WorkflowProperties(true, true, Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 2097152, 3);

    @Test void catalogFetchesOnceAndDoesNotEvaluateMissingMediaMappings() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        when(client.fetch(any(WorkflowHttpClient.Request.class), anyLong())).thenReturn(bytes("{\"token\":\"t\",\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}"));
        var entries = new WorkflowRunner(client, PROPERTIES).catalog(definition);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().key()).matches("[a-f0-9]{64}");
        assertThat(entries.getFirst().title()).isEqualTo("News");
        verify(client).fetch(any(WorkflowHttpClient.Request.class), anyLong());
        verify(client, never()).checkMedia(any(), anyLong());
    }

    @Test void resolveUsesFreshTokenAndStableEntryAfterReorderingAndChecksMediaOnce() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        when(client.fetch(any(WorkflowHttpClient.Request.class), anyLong())).thenReturn(
                bytes("{\"token\":\"old-secret\",\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}"),
                bytes("{\"token\":\"fresh-secret\",\"items\":[{\"id\":\"other\",\"title\":\"Other\"},{\"id\":\"news\",\"title\":\"New News\"}]}"));
        var runner = new WorkflowRunner(client, PROPERTIES);
        var key = runner.catalog(definition).getFirst().key();
        var media = runner.resolve(definition, key);
        assertThat(media.url()).isEqualTo(URI.create("https://media.example/play?id=news&token=fresh-secret"));
        assertThat(media.title()).isEqualTo("New News");
        assertThat(media.mimeType()).isEqualTo("video/mp4");
        assertThat(media.toString()).doesNotContain("media.example", "fresh-secret", "New News", "video/mp4");
        verify(client, times(2)).fetch(any(WorkflowHttpClient.Request.class), anyLong());
        verify(client, times(1)).checkMedia(eq(media.url()), anyLong());
    }

    @Test void duplicateDisappearedAndMissingMappingsFailBeforeMediaValidation() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        var runner = new WorkflowRunner(client, PROPERTIES);
        when(client.fetch(any(), anyLong())).thenReturn(bytes("{\"token\":\"t\",\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}"));
        var key = runner.catalog(definition).getFirst().key();
        var cases = java.util.Map.of(
                "{\"token\":\"t\",\"items\":[]}", "no longer available",
                "{\"token\":\"t\",\"items\":[{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"news\",\"title\":\"Duplicate\"}]}", "has duplicate ID",
                "{\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}", "mapping C");
        cases.forEach((body, expected) -> {
            when(client.fetch(any(), anyLong())).thenReturn(bytes(body));
            assertThatThrownBy(() -> runner.resolve(definition, key)).isInstanceOf(WorkflowException.class)
                    .hasMessageContaining(expected);
        });
        verify(client, never()).checkMedia(any(), anyLong());
    }

    @Test void aDisappearedEntryNamesTheSelectStage() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        when(client.fetch(any(), anyLong())).thenReturn(bytes("{\"token\":\"t\",\"items\":[]}"));
        assertThatThrownBy(() -> new WorkflowRunner(client, PROPERTIES).resolve(definition, "gone"))
                .hasMessage("Choose entries: This item is no longer available; refresh the Dashboard");
    }

    @Test void aSingleTileWithoutCallsResolvesItsFixedUrlWithoutFetchingAnyCall() {
        var client = mock(WorkflowHttpClient.class);
        var draft = WorkflowFixtures.singleWith(List.of());
        var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1, draft);
        var media = new WorkflowRunner(client, PROPERTIES).resolve(definition, "single");
        assertThat(media.url()).isEqualTo(URI.create("https://media.example/play"));
        verify(client, never()).fetch(any(WorkflowHttpClient.Request.class), anyLong());
        verify(client).checkMedia(eq(media.url()), anyLong());
    }

    @Test void singleResolutionFetchesOnlyJsonAndNeverRequestsMedia() throws Exception {
        try (var server = new FakeWorkflowServer()) {
            server.respond("/json", 200, "{\"id\":\"news\",\"token\":\"secret\"}");
            var draft = WorkflowFixtures.single(server.url("/json"));
            draft = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(), draft.calls(), null,
                    draft.tile(), new WorkflowDraft.Cast(server.url("/media").toString() + "?id={A}&token={C}", "audio/aac"));
            var definition = new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1, draft);
            var properties = new WorkflowProperties(true, true, Duration.ofSeconds(5), Duration.ofSeconds(15), 4, 2097152, 3);
            try (var client = new WorkflowHttpClient(properties, new OutboundAddressPolicy(true, InetAddress::getAllByName))) {
                var media = new WorkflowRunner(client, PROPERTIES).resolve(definition, "single");
                assertThat(media.url().getPath()).isEqualTo("/media");
                assertThat(media.mimeType()).isEqualTo("audio/aac");
                assertThat(server.count("/json")).isEqualTo(1);
                assertThat(server.count("/media")).isZero();
            }
        }
    }

    /** Every host resolves to 127.0.0.1: the fake server is reachable and media.example passes the address check. */
    private static WorkflowHttpClient loopbackClient() {
        return new WorkflowHttpClient(PROPERTIES,
                new OutboundAddressPolicy(true, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")}));
    }

    private static WorkflowDefinition chain(FakeWorkflowServer server) {
        return new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, WorkflowIntegrationFixture.ID, 1,
                WorkflowFixtures.chain(server.url("/")));
    }

    private static void list(FakeWorkflowServer server, String token) {
        server.respond("/list", 200, "{\"token\":\"" + token + "\",\"items\":["
                + "{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"music\",\"title\":\"Music\"}]}");
    }

    @Test void refreshRunsTheListOnceAndTheArtworkCallPerEntryButNeverThePlayCall() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            server.respond("/images/music", 200, "{\"url\":\"https://images.example/music.png\"}");
            var entries = new WorkflowRunner(http, PROPERTIES).catalog(chain(server));
            assertThat(entries).extracting(WorkflowRunner.CatalogEntry::title).containsExactly("News", "Music");
            assertThat(entries).extracting(e -> e.artwork().toString())
                    .containsExactly("https://images.example/news.png", "https://images.example/music.png");
            assertThat(server.count("/list")).isEqualTo(1);
            assertThat(server.count("/images/news")).isEqualTo(1);
            assertThat(server.count("/stream/news")).isZero();
        }
    }

    @Test void unsafeArtworkFromAnEntryCallFallsBackToThePlaceholder() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"http://192.168.1.2/private.png\"}");
            server.respond("/images/music", 404, "{}");
            var refresh = new WorkflowRunner(http, PROPERTIES).refresh(chain(server),
                    new WorkflowCalls.Run(Duration.ofSeconds(10), 3));
            assertThat(refresh.entries()).extracting(WorkflowRunner.CatalogEntry::artwork).containsOnlyNulls();
            assertThat(refresh.artworkOmitted()).isTrue();
            assertThat(refresh.problems()).containsExactly("Call images \u00b7 entry \"Music\": server returned HTTP 404");
        }
    }

    @Test void anUnexpectedFailureInOneTileFallsBackWithoutExposingItsText() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            var real = new WorkflowCalls(http);
            try (var _ = mockConstruction(WorkflowCalls.class, (calls, context) -> when(calls.run(any(), any(), any(), any(), any()))
                    .thenAnswer(call -> {
                        if ("Music".equals(call.getArgument(4))) throw new IllegalStateException("boom-secret");
                        return real.run(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
                    }))) {
                var refresh = new WorkflowRunner(http, PROPERTIES).refresh(chain(server),
                        new WorkflowCalls.Run(Duration.ofSeconds(10), 3));
                assertThat(refresh.entries()).extracting(WorkflowRunner.CatalogEntry::title).containsExactly("News", "Music");
                assertThat(refresh.entries().getFirst().artwork()).isNotNull();
                assertThat(refresh.entries().getLast().artwork()).isNull();
                assertThat(refresh.problems()).containsExactly("Entry \"Music\": could not be read");
            }
        }
    }

    @Test void theEntryLimitDropsToFiftyWhenAPerEntryCallRunsAtRefresh() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            var items = new StringBuilder();
            for (int i = 0; i < 51; i++) items.append(i == 0 ? "" : ",").append("{\"id\":\"e").append(i).append("\",\"title\":\"E\"}");
            server.respond("/list", 200, "{\"token\":\"t\",\"items\":[" + items + "]}");
            assertThatThrownBy(() -> new WorkflowRunner(http, PROPERTIES).catalog(chain(server)))
                    .hasMessage("Choose entries: the list has 51 entries; the limit is 50");
            assertThat(server.count("/images/e1")).isZero();
        }
    }

    @Test void playRefetchesTheListFindsTheEntryAndRunsOnlyItsStreamCall() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            list(server, "t-1");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            server.respond("/images/music", 200, "{\"url\":\"https://images.example/music.png\"}");
            var runner = new WorkflowRunner(http, PROPERTIES);
            var definition = chain(server);
            String key = runner.catalog(definition).getFirst().key();
            list(server, "t-2");
            server.respond("/stream/news", 200, "{\"path\":\"news-hd.m3u8\"}");
            var media = runner.resolve(definition, key);
            assertThat(media.url()).hasToString("https://media.example/play/news-hd.m3u8?t=t-2");
            assertThat(media.title()).isEqualTo("News");
            assertThat(server.requests("/stream/news").getFirst().header("Authorization")).isEqualTo("Bearer t-2");
            assertThat(server.count("/stream/music")).isZero();
            assertThat(server.count("/images/news")).isEqualTo(1);
        }
    }

    @Test void migratedHeaderWithBracesIsSentUnchanged() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = loopbackClient()) {
            server.respond("/feed", 200, "{\"id\":\"news\",\"token\":\"t\"}");
            String v1 = """
                    {"schemaVersion":1,"id":"w-0123456789ab","revision":1,"draft":{
                      "name":"News","enabled":true,"mode":"SINGLE","kind":"VIDEO",
                      "fetch":{"url":"%s","headers":[{"name":"X-Filter","value":"{\\"a\\":1}"}]},"listing":null,
                      "tile":{"title":"News","subtitle":null,"artwork":null},
                      "variables":[{"name":"A","scope":"ROOT","pointer":"/id","sensitive":false}],
                      "cast":{"template":"https://media.example/play?id={A}","mimeType":"video/mp4"}}}
                    """.formatted(server.url("/feed"));
            new WorkflowRunner(http, PROPERTIES).resolve(new WorkflowCodec().decode(v1), "single");
            assertThat(server.requests("/feed").getFirst().header("X-Filter")).isEqualTo("{\"a\":1}");
        }
    }

    private static byte[] bytes(String body) { return body.getBytes(StandardCharsets.UTF_8); }
}
