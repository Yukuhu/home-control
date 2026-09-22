package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowRunnerTest {
    @Test void catalogFetchesOnceAndDoesNotEvaluateMissingMediaMappings() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(1, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        when(client.fetch(definition.draft().fetch())).thenReturn(bytes("{\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}"));
        var entries = new WorkflowRunner(client).catalog(definition);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().key()).matches("[a-f0-9]{64}");
        assertThat(entries.getFirst().title()).isEqualTo("News");
        verify(client).fetch(definition.draft().fetch());
        verify(client, never()).checkMedia(any());
    }

    @Test void resolveUsesFreshTokenAndStableEntryAfterReorderingAndChecksMediaOnce() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(1, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        when(client.fetch(definition.draft().fetch())).thenReturn(
                bytes("{\"token\":\"old-secret\",\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}"),
                bytes("{\"token\":\"fresh-secret\",\"items\":[{\"id\":\"other\",\"title\":\"Other\"},{\"id\":\"news\",\"title\":\"New News\"}]}"));
        var runner = new WorkflowRunner(client);
        var key = runner.catalog(definition).getFirst().key();
        var media = runner.resolve(definition, key);
        assertThat(media.url()).isEqualTo(URI.create("https://media.example/play?id=news&token=fresh-secret"));
        assertThat(media.title()).isEqualTo("New News");
        assertThat(media.mimeType()).isEqualTo("video/mp4");
        assertThat(media.toString()).doesNotContain("media.example", "fresh-secret", "New News", "video/mp4");
        verify(client, times(2)).fetch(definition.draft().fetch());
        verify(client, times(1)).checkMedia(media.url());
    }

    @Test void duplicateDisappearedAndMissingMappingsFailBeforeMediaValidation() {
        var client = mock(WorkflowHttpClient.class);
        var definition = new WorkflowDefinition(1, WorkflowIntegrationFixture.ID, 1, WorkflowFixtures.generated());
        var runner = new WorkflowRunner(client);
        when(client.fetch(any())).thenReturn(bytes("{\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}"));
        var key = runner.catalog(definition).getFirst().key();
        for (String body : List.of("{\"items\":[]}",
                "{\"items\":[{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"news\",\"title\":\"Duplicate\"}]}",
                "{\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}")) {
            when(client.fetch(any())).thenReturn(bytes(body));
            assertThatThrownBy(() -> runner.resolve(definition, key)).isInstanceOf(WorkflowException.class);
        }
        verify(client, never()).checkMedia(any());
    }

    @Test void singleResolutionFetchesOnlyJsonAndNeverRequestsMedia() throws Exception {
        try (var server = new FakeWorkflowServer()) {
            server.respond("/json", 200, "{\"id\":\"news\",\"token\":\"secret\"}");
            var draft = WorkflowFixtures.single(server.url("/json"));
            draft = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(), draft.fetch(), null,
                    draft.tile(), draft.variables(), new WorkflowDraft.Cast(server.url("/media").toString() + "?id={A}&token={C}", "audio/aac"));
            var definition = new WorkflowDefinition(1, WorkflowIntegrationFixture.ID, 1, draft);
            var properties = new WorkflowProperties(true, true, Duration.ofSeconds(5), Duration.ofSeconds(15), 4, 2097152, 3);
            try (var client = new WorkflowHttpClient(properties, new WorkflowUrlPolicy(true, InetAddress::getAllByName))) {
                var media = new WorkflowRunner(client).resolve(definition, "single");
                assertThat(media.url().getPath()).isEqualTo("/media");
                assertThat(media.mimeType()).isEqualTo("audio/aac");
                assertThat(server.count("/json")).isEqualTo(1);
                assertThat(server.count("/media")).isZero();
            }
        }
    }

    private static byte[] bytes(String body) { return body.getBytes(StandardCharsets.UTF_8); }
}
