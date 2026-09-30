package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.testsupport.FakeLoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowTestServiceTest {
    private static final WorkflowProperties PROPERTIES =
            new WorkflowProperties(true, true, Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 2097152, 3);
    final WorkflowStore store = mock(WorkflowStore.class);
    final FakeLoginContext request = FakeLoginContext.loggedInBrowser();
    final String id = "w-0123456789ab";
    WorkflowDefinition saved;

    @BeforeEach void setup() {
        when(store.find(id)).thenAnswer(call -> Optional.of(saved));
    }

    @Test void authenticationAndRevisionAreCheckedBeforeAnyFetch() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            var runner = mock(WorkflowRunner.class);
            var service = new WorkflowTestService(store, runner, http);
            saved = definition(7, WorkflowFixtures.chain(server.url("/")));
            assertThatThrownBy(() -> service.test(id, 7, FakeLoginContext.loggedOutBrowser()))
                    .isInstanceOf(LoginRequiredException.class);
            verifyNoInteractions(store, runner);
            assertThatThrownBy(() -> service.test(id, 6, request)).isInstanceOf(WorkflowException.class);
            verifyNoInteractions(runner);
            assertThat(server.count("/list")).isZero();
        }
    }

    @Test void generatedChainReportsRefreshPlayMaskedSamplesAndEntryProblems() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/list", 200, "{\"token\":\"secret-token\",\"items\":[{\"id\":\"news\",\"title\":\"News\"},{\"id\":\"music\",\"title\":\"Music\"}]}");
            server.respond("/images/news", 200, "{\"url\":\"https://images.example/news.png\"}");
            server.respond("/images/music", 404, "{}");
            server.respond("/stream/news", 200, "{\"path\":\"secret-path-news\"}");
            server.respond("/stream/music", 200, "{\"path\":\"secret-path-music\"}");
            var stored = save(WorkflowFixtures.chain(server.url("/")));
            var result = service(http).test(stored.id(), stored.revision(), request);
            assertThat(result.stages()).extracting(WorkflowTestService.StageView::name).containsExactly("Refresh", "Play");
            assertThat(result.stages()).allMatch(WorkflowTestService.StageView::success);
            assertThat(result.totalEntries()).isEqualTo(2);
            assertThat(result.samples()).extracting(WorkflowTestService.SampleView::title).containsExactly("News", "Music");
            assertThat(result.samples().getFirst().variables()).contains("token = •••", "path = •••", "id = news");
            assertThat(result.warnings()).contains("Call images · entry \"Music\": server returned HTTP 404");
            assertThat(result.toString()).doesNotContain("secret-token", "secret-path");
        }
    }

    @Test void aFailingSharedCallIsNamedAndLeavesNoSamples() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/list", 500, "{}");
            var stored = save(WorkflowFixtures.chain(server.url("/")));
            var result = service(http).test(stored.id(), stored.revision(), request);
            assertThat(result.stages()).last().satisfies(stage -> {
                assertThat(stage.name()).isEqualTo("Call list");
                assertThat(stage.success()).isFalse();
                assertThat(stage.message()).isEqualTo("Call list: server returned HTTP 500");
            });
            assertThat(result.samples()).isEmpty();
        }
    }

    @Test void countsAllEntriesButShowsAtMostFiveSamples() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/list", 200, "{\"token\":\"t\",\"items\":[" + IntStream.range(0, 8)
                    .mapToObj(i -> "{\"id\":\"e" + i + "\",\"title\":\"Title " + i + "\"}").collect(Collectors.joining(",")) + "]}");
            for (int i = 0; i < 8; i++) {
                server.respond("/images/e" + i, 200, "{\"url\":\"https://images.example/a.png\"}");
                server.respond("/stream/e" + i, 200, "{\"path\":\"p" + i + "\"}");
            }
            var stored = save(WorkflowFixtures.chain(server.url("/")));
            var result = service(http).test(stored.id(), stored.revision(), request);
            assertThat(result.totalEntries()).isEqualTo(8);
            assertThat(result.samples()).hasSize(5);
            assertThat(server.count("/stream/e5")).isZero();
        }
    }

    @Test void singleModeBuildsOneEntryFromTheSavedTileAndMasksTheUrl() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/one", 200, "{\"id\":\"item1\",\"token\":\"secret-token\"}");
            var single = WorkflowFixtures.single(server.url("/one"));
            var stored = save(new WorkflowDraft(single.name(), single.enabled(), single.mode(), single.kind(),
                    single.calls(), null, new WorkflowDraft.Tile("Radio", "Live", null), single.cast()));
            var result = service(http).test(stored.id(), stored.revision(), request);
            assertThat(result.stages()).extracting(WorkflowTestService.StageView::name).containsExactly("Play");
            assertThat(result.totalEntries()).isEqualTo(1);
            var sample = result.samples().getFirst();
            assertThat(sample.title()).isEqualTo("Radio");
            assertThat(sample.subtitle()).isEqualTo("Live");
            assertThat(sample.maskedUrl()).contains("id=item1", "token=•••").doesNotContain("secret-token");
        }
    }

    @Test void disabledSavedWorkflowCanBeTestedButConcurrentEditRejectsResult() throws Exception {
        try (var server = new FakeWorkflowServer(); var http = client()) {
            server.respond("/list", 200, "{\"token\":\"private\",\"items\":[]}");
            saved = definition(7, WorkflowFixtures.chain(server.url("/")).withEnabled(false));
            var service = service(http);
            assertThat(service.test(id, 7, request).totalEntries()).isZero();
            when(store.find(id)).thenReturn(Optional.of(saved), Optional.of(definition(8, saved.draft())));
            assertThatThrownBy(() -> service.test(id, 7, request)).isInstanceOf(WorkflowException.class);
        }
    }

    @Test void anUnexpectedExceptionGivesAGenericMessageWithoutItsText() {
        var runner = mock(WorkflowRunner.class);
        when(runner.refreshRun()).thenThrow(new IllegalStateException("upstream-secret", new IllegalArgumentException("cause-secret")));
        saved = definition(7, WorkflowFixtures.chain(java.net.URI.create("https://api.example/")));
        var result = new WorkflowTestService(store, runner, mock(WorkflowHttpClient.class)).test(id, 7, request);
        assertThat(result.stages()).last().satisfies(stage -> {
            assertThat(stage.name()).isEqualTo("Refresh");
            assertThat(stage.success()).isFalse();
        });
        assertThat(result.toString()).doesNotContain("upstream-secret", "cause-secret");
    }

    @Test void aFailingMediaCheckIsReportedAsPlayWithoutTheAddressOrSecrets() throws Exception {
        try (var server = new FakeWorkflowServer(); var real = client()) {
            var http = mock(WorkflowHttpClient.class);
            server.respond("/list", 200, "{\"token\":\"token-secret\",\"items\":[{\"id\":\"a\",\"title\":\"News\"}]}");
            server.respond("/images/a", 200, "{}");
            server.respond("/stream/a", 200, "{\"path\":\"path-secret\"}");
            when(http.fetch(any(WorkflowHttpClient.Request.class), anyLong())).thenAnswer(call ->
                    real.fetch(call.getArgument(0), call.getArgument(1)));
            doThrow(new RuntimeException("private-marker")).when(http).checkMedia(any(), anyLong());
            var stored = save(WorkflowFixtures.chain(server.url("/")));
            var result = new WorkflowTestService(store, new WorkflowRunner(http, PROPERTIES), http)
                    .test(stored.id(), stored.revision(), request);
            assertThat(result.samples()).isEmpty();
            assertThat(result.stages()).last().satisfies(stage -> {
                assertThat(stage.name()).isEqualTo("Play");
                assertThat(stage.success()).isFalse();
            });
            assertThat(result.toString()).doesNotContain("private-marker", "token-secret", "path-secret", "media.example");
        }
    }

    private WorkflowDefinition save(WorkflowDraft draft) {
        saved = definition(7, draft);
        return saved;
    }

    private WorkflowDefinition definition(long revision, WorkflowDraft draft) {
        return new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, id, revision, draft);
    }

    private WorkflowTestService service(WorkflowHttpClient http) {
        return new WorkflowTestService(store, new WorkflowRunner(http, PROPERTIES), http);
    }

    private static WorkflowHttpClient client() {
        return new WorkflowHttpClient(PROPERTIES,
                new WorkflowUrlPolicy(true, host -> new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")}));
    }
}
