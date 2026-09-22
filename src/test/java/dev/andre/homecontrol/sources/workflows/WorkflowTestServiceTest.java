package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowTestServiceTest {
    final WorkflowStore store = mock(WorkflowStore.class);
    final LoginService login = mock(LoginService.class);
    final WorkflowHttpClient http = mock(WorkflowHttpClient.class);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final WorkflowTestService service = new WorkflowTestService(store, login, http);
    final String id = "w-0123456789ab";
    WorkflowDefinition saved;

    @BeforeEach void setup() {
        saved = new WorkflowDefinition(1, id, 7, WorkflowFixtures.generated());
        when(store.find(id)).thenAnswer(call -> Optional.of(saved));
        when(login.isAuthenticated(request)).thenReturn(true);
    }

    @Test void authenticationAndRevisionAreCheckedBeforeAnyFetch() {
        when(login.isAuthenticated(request)).thenReturn(false);
        assertThatThrownBy(() -> service.test(id, 7, request)).isInstanceOf(LoginRequiredException.class);
        verifyNoInteractions(store, http);
        when(login.isAuthenticated(request)).thenReturn(true);
        assertThatThrownBy(() -> service.test(id, 6, request)).isInstanceOf(WorkflowException.class);
        verifyNoInteractions(http);
    }

    @Test void fetchesOnceCountsAllEntriesAndMasksFiveSamples() {
        body("{\"token\":\"secret-token\",\"items\":[" + java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> "{\"id\":\"item" + i + "\",\"title\":\"Title " + i + "\"}")
                .collect(java.util.stream.Collectors.joining(",")) + "]}");
        var result = service.test(id, 7, request);
        assertThat(result.totalEntries()).isEqualTo(8);
        assertThat(result.samples()).hasSize(5);
        assertThat(result.samples().getFirst().title()).isEqualTo("Title 0");
        assertThat(result.samples().getFirst().variables()).containsExactly("A = item0", "C = •••");
        assertThat(result.samples().getFirst().maskedUrl()).contains("id=item0", "token=•••").doesNotContain("/play");
        assertThat(result.toString()).doesNotContain("secret-token", "saved-secret", "api.example", "JsonNode");
        verify(http).fetch(saved.draft().fetch());
        verify(http, times(5)).checkMedia(any(URI.class));
        verifyNoMoreInteractions(http);
    }

    @Test void disabledSavedWorkflowCanBeTestedButConcurrentEditRejectsResult() {
        var d = saved.draft();
        saved = new WorkflowDefinition(1, id, 7, new WorkflowDraft(d.name(), false, d.mode(), d.kind(),
                d.fetch(), d.listing(), d.tile(), d.variables(), d.cast()));
        body("{\"token\":\"private\",\"items\":[]}");
        assertThat(service.test(id, 7, request).totalEntries()).isZero();
        when(http.fetch(any())).thenAnswer(call -> {
            saved = new WorkflowDefinition(1, id, 8, saved.draft());
            return "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        });
        assertThatThrownBy(() -> service.test(id, 7, request)).isInstanceOf(WorkflowException.class);
    }

    @Test void errorsHaveStageContextWithoutUpstreamPayloadOrExceptionText() {
        body("{\"private-json-token\":");
        var result = service.test(id, 7, request);
        assertThat(result.stages()).anySatisfy(stage -> {
            assertThat(stage.name()).isEqualTo("Parse JSON");
            assertThat(stage.success()).isFalse();
        });
        assertThat(result.toString()).doesNotContain("private-json-token");
        when(http.fetch(any())).thenThrow(new RuntimeException("upstream-secret", new IllegalArgumentException("cause-secret")));
        assertThat(service.test(id, 7, request).toString()).doesNotContain("upstream-secret", "cause-secret");
    }

    @Test void invalidSelectedArtworkProducesSafeWarningAndMappingFailuresStaySafe() {
        var d = saved.draft();
        saved = new WorkflowDefinition(1, id, 7, new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(),
                d.fetch(), new WorkflowDraft.Listing("/items", "/id", "/title", null, "/art"), null,
                d.variables(), d.cast()));
        body("{\"token\":\"private\",\"items\":[{\"id\":\"a\",\"title\":\"News\",\"art\":\"http://secret-art/token\"}]}");
        var result = service.test(id, 7, request);
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.toString()).doesNotContain("secret-art", "private");
        body("{\"items\":[{\"id\":\"a\",\"title\":\"News\"}]}");
        assertThat(service.test(id, 7, request).stages()).anySatisfy(stage -> {
            assertThat(stage.name()).isEqualTo("Map fields");
            assertThat(stage.success()).isFalse();
        });
    }

    @Test void invalidArrayPointerAndMediaFailureHaveSafeStageResults() {
        var d = saved.draft();
        saved = new WorkflowDefinition(1, id, 7, new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(),
                d.fetch(), new WorkflowDraft.Listing("invalid-pointer-private-marker", "/id", "/title", null, null), null,
                d.variables(), d.cast()));
        body("{\"items\":[]}");
        var result = service.test(id, 7, request);
        assertThat(result.stages()).anySatisfy(stage -> {
            assertThat(stage.name()).isEqualTo("Choose entries"); assertThat(stage.success()).isFalse();
        });
        assertThat(result.toString()).doesNotContain("private-marker");
        saved = new WorkflowDefinition(1, id, 7, WorkflowFixtures.generated());
        body("{\"token\":\"token-secret\",\"items\":[{\"id\":\"a\",\"title\":\"News\"}]}");
        doThrow(new RuntimeException("private-marker")).when(http).checkMedia(any());
        var failed = service.test(id, 7, request);
        assertThat(failed.samples()).isEmpty();
        assertThat(failed.toString()).doesNotContain("private-marker", "token-secret");
        assertThat(failed.stages()).anySatisfy(stage -> {
            assertThat(stage.name()).isEqualTo("Build media URL"); assertThat(stage.success()).isFalse();
        });
    }

    @Test void safeFailuresExplainHttpStatusBusyAdmissionMappingAndDuplicateEntry() {
        for (String detail : List.of("server returned HTTP 403", "busy; try again later", "request timed out")) {
            doThrow(new WorkflowException(WorkflowException.Stage.FETCH, detail)).when(http).fetch(any());
            var result = service.test(id, 7, request);
            assertThat(result.stages().getLast().message()).contains(detail);
            assertThat(result.samples()).isEmpty();
        }
        reset(http);
        body("{\"items\":[{\"id\":\"a\",\"title\":\"News\"}]}");
        assertThat(service.test(id, 7, request).stages().getLast().message()).contains("mapping C has no scalar value");
        body("{\"token\":\"private-token\",\"items\":[{\"id\":1,\"title\":\"A\"},{\"id\":1.0,\"title\":\"B\"}]}");
        var result = service.test(id, 7, request);
        assertThat(result.stages().getLast().message()).contains("entry 1 has duplicate ID");
        assertThat(result.toString()).doesNotContain("private-token");
    }

    void body(String body) { when(http.fetch(any())).thenReturn(body.getBytes(StandardCharsets.UTF_8)); }
}
