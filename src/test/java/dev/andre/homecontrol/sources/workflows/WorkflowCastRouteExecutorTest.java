package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.*;
import dev.andre.homecontrol.core.playback.*;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static dev.andre.homecontrol.sources.workflows.WorkflowIntegrationFixture.ID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowCastRouteExecutorTest {
    private final WorkflowIntegrationFixture fixture = new WorkflowIntegrationFixture(false);
    private final WorkflowRunner runner = mock(WorkflowRunner.class);
    private final DeviceManager devices = mock(DeviceManager.class);
    private final RailPreferences preferences = mock(RailPreferences.class);
    private final Device tv = new Device("tv", "TV", DeviceKind.ANDROID_TV, "10.0.0.1", Map.of(), Instant.now());
    private final Route.WorkflowCast route = new Route.WorkflowCast(ID, 1, "single");
    private final WorkflowRunner.ResolvedMedia media = new WorkflowRunner.ResolvedMedia(
            URI.create("https://media.example/play?token=fresh-secret"), "audio/aac", "News");
    private WorkflowCastRouteExecutor executor;

    @BeforeEach void setup() {
        when(preferences.sourceEnabled("workflows")).thenReturn(true);
        when(devices.capabilities("tv")).thenReturn(Set.of(Capability.CAST_RECEIVER));
        when(runner.resolve(fixture.definition, "single")).thenReturn(media);
        executor = new WorkflowCastRouteExecutor(fixture.store, runner, devices, preferences);
    }

    @Test void sendsOneDefaultReceiverLoadWithFreshUrlMimeAndTitle() {
        executor.execute(route, tv);
        var action = ArgumentCaptor.forClass(Action.class);
        verify(devices).execute(eq("tv"), action.capture());
        var cast = (Action.CastLoad) action.getValue();
        assertThat(cast.receiverAppId()).isEqualTo("CC1AD845");
        assertThat(cast.load()).containsEntry("autoplay", true);
        @SuppressWarnings("unchecked") var payload = (Map<String, Object>) cast.load().get("media");
        assertThat(payload).containsEntry("contentUrl", "https://media.example/play?token=fresh-secret")
                .containsEntry("contentId", "https://media.example/play?token=fresh-secret")
                .containsEntry("contentType", "audio/aac");
        assertThat(payload.get("metadata")).isEqualTo(Map.of("metadataType", 0, "title", "News"));
        verify(runner).resolve(fixture.definition, "single");
    }

    @Test void generatedCatalogPreviewAndPlayUseOnlyFreshTokenAtTheDevice() throws Exception {
        try (var server = new FakeWorkflowServer()) {
            server.respond("/catalog", 200, "{\"token\":\"old-secret\",\"items\":[{\"id\":\"news\",\"title\":\"News\"}]}");
            var draft = WorkflowFixtures.generated();
            draft = new WorkflowDraft(draft.name(), true, draft.mode(), draft.kind(),
                    new WorkflowDraft.Fetch(server.url("/catalog").toString(), List.of()), draft.listing(), null,
                    draft.variables(), new WorkflowDraft.Cast(server.url("/media").toString() + "?id={A}&token={C}", "video/mp4"));
            var generated = new WorkflowIntegrationFixture(draft);
            var properties = new WorkflowProperties(true, true, java.time.Duration.ofSeconds(5),
                    java.time.Duration.ofSeconds(15), 4, 2097152, 3);
            try (var http = new WorkflowHttpClient(properties, new WorkflowUrlPolicy(true, java.net.InetAddress::getAllByName))) {
                var realRunner = new WorkflowRunner(http);
                var source = new WorkflowContentSource(generated.store, realRunner,
                        new WorkflowCatalogs(generated.store), preferences);
                var item = source.rail(ID).items().getFirst();
                var cast = new WorkflowCastRouteExecutor(generated.store, realRunner, devices, preferences);
                when(devices.device("tv")).thenReturn(Optional.of(tv));
                var playback = new PlaybackService(devices, new PlaybackPlanner(List.of(new WorkflowCastStrategy())),
                        List.of(), List.of(cast));
                assertThat(source.item(item.id())).contains(item);
                assertThat(playback.plan(item, "tv")).isInstanceOf(Route.WorkflowCast.class);
                assertThat(playback.preview(item, "tv").routes()).hasSize(1);
                assertThat(server.count("/catalog")).isEqualTo(1);
                server.respond("/catalog", 200, "{\"token\":\"fresh-secret\",\"items\":[{\"id\":\"other\",\"title\":\"Other\"},{\"id\":\"news\",\"title\":\"Fresh News\"}]}");
                assertThat(playback.attempt(item, "tv", Set.of())).isInstanceOf(PlayAttempt.Played.class);
                var action = ArgumentCaptor.forClass(Action.class);
                verify(devices).execute(eq("tv"), action.capture());
                @SuppressWarnings("unchecked") var payload = (Map<String, Object>) ((Action.CastLoad) action.getValue()).load().get("media");
                assertThat(payload.get("contentUrl")).isEqualTo(server.url("/media") + "?id=news&token=fresh-secret");
                assertThat(payload.toString()).doesNotContain("old-secret");
                assertThat(payload.get("metadata")).isEqualTo(Map.of("metadataType", 0, "title", "Fresh News"));
                assertThat(server.count("/catalog")).isEqualTo(2);
                assertThat(server.count("/media")).isZero();
                // A subsequent user retry fetches again; a missing entry never sends another command.
                server.respond("/catalog", 200, "{\"token\":\"secret\",\"items\":[]}");
                assertThat(playback.attempt(item, "tv", Set.of())).isInstanceOf(PlayAttempt.Failed.class);
                verify(devices, times(1)).execute(any(), any());
            }
        }
    }

    @Test void wrongDeviceAndDisabledSourceNeverFetchOrSend() {
        when(devices.capabilities("tv")).thenReturn(Set.of(Capability.APP_LINK));
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(UnsupportedActionException.class);
        when(devices.capabilities("tv")).thenReturn(Set.of(Capability.CAST_RECEIVER));
        when(preferences.sourceEnabled("workflows")).thenReturn(false);
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(ActionFailedException.class);
        verifyNoInteractions(runner);
        verify(devices, never()).execute(any(), any());
    }

    @Test void staleDisabledAndDeletedWorkflowsNeverFetchOrSend() {
        fixture.store.update(ID, 1, fixture.definition.draft(), fixture.request);
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(ActionFailedException.class);
        fixture.store.setEnabled(ID, 2, false, fixture.request);
        assertThatThrownBy(() -> executor.execute(new Route.WorkflowCast(ID, 3, "single"), tv)).isInstanceOf(ActionFailedException.class);
        fixture.store.remove(ID, 3, fixture.request);
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(ActionFailedException.class);
        verifyNoInteractions(runner);
        verify(devices, never()).execute(any(), any());
    }

    @Test void fetchFailureBecomesFailedAttemptAndNeverSends() {
        when(runner.resolve(any(), any())).thenThrow(new WorkflowException(WorkflowException.Stage.FETCH, "request failed"));
        when(devices.device("tv")).thenReturn(Optional.of(tv));
        var service = new PlaybackService(devices, new PlaybackPlanner(List.of(new WorkflowCastStrategy())), List.of(), List.of(executor));
        var item = new ContentItem(ID, "workflows", ContentKind.VIDEO, "News", null, null,
                List.of(new PlayableRef.WorkflowCast(ID, 1, "single")));
        assertThat(service.attempt(item, "tv", Set.of())).isInstanceOfSatisfying(PlayAttempt.Failed.class,
                failed -> assertThat(failed.cause()).hasMessageContaining("Fetch JSON"));
        verify(devices, never()).execute(any(), any());
    }

    @Test void editsDuringResolutionCancelDispatch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(runner.resolve(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return media;
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var play = workers.submit(() -> executor.execute(route, tv));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                fixture.store.update(ID, 1, fixture.definition.draft(), fixture.request);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> play.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ActionFailedException.class);
        }
        verify(devices, never()).execute(any(), any());
    }

    @Test void sourceDisabledDuringResolutionCancelsDispatch() {
        when(runner.resolve(any(), any())).thenAnswer(call -> {
            when(preferences.sourceEnabled("workflows")).thenReturn(false);
            return media;
        });
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(ActionFailedException.class);
        verify(devices, never()).execute(any(), any());
    }

    @Test void capabilityRemovedDuringResolutionCancelsDispatch() {
        when(runner.resolve(any(), any())).thenAnswer(call -> {
            when(devices.capabilities("tv")).thenReturn(Set.of(Capability.APP_LINK));
            return media;
        });
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(UnsupportedActionException.class);
        verify(devices, never()).execute(any(), any());
    }

    @Test void receiverFailuresAreRedactedAndOfflineUnsupportedTypesArePreserved() {
        doThrow(new ActionFailedException("Receiver refused https://media.example/?token=secret-marker"))
                .when(devices).execute(any(), any());
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(ActionFailedException.class)
                .hasMessageNotContaining("secret-marker").hasMessageNotContaining("media.example");
        doThrow(new DeviceOfflineException("offline")).when(devices).execute(any(), any());
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(DeviceOfflineException.class);
        doThrow(new UnsupportedActionException("unsupported")).when(devices).execute(any(), any());
        assertThatThrownBy(() -> executor.execute(route, tv)).isInstanceOf(UnsupportedActionException.class);
    }
}
