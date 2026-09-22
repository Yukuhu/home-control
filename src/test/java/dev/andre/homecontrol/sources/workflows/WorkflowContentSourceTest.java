package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.playback.*;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
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

class WorkflowContentSourceTest {
    private final WorkflowRunner runner = mock(WorkflowRunner.class);
    private final RailPreferences preferences = mock(RailPreferences.class);
    private final String key = "a".repeat(64);

    private WorkflowContentSource source(WorkflowIntegrationFixture fixture, WorkflowCatalogs catalogs) {
        when(preferences.sourceEnabled("workflows")).thenReturn(true);
        return new WorkflowContentSource(fixture.store, runner, catalogs, preferences);
    }

    @Test void singleRailItemPlanAndPreviewNeverFetch() {
        var fixture = new WorkflowIntegrationFixture(false);
        var source = source(fixture, new WorkflowCatalogs(fixture.store));
        assertThat(source.rails()).hasSize(1);
        var item = source.rail(ID).items().getFirst();
        assertThat(source.item(ID)).contains(item);
        var devices = mock(DeviceManager.class);
        var device = new Device("tv", "TV", DeviceKind.ANDROID_TV, "10.0.0.1", Map.of(), Instant.now());
        when(devices.device("tv")).thenReturn(Optional.of(device));
        when(devices.capabilities("tv")).thenReturn(Set.of(Capability.CAST_RECEIVER));
        var service = new PlaybackService(devices, new PlaybackPlanner(List.of(new WorkflowCastStrategy())));
        assertThat(service.plan(item, "tv")).isInstanceOf(Route.WorkflowCast.class);
        assertThat(service.preview(item, "tv").routes()).hasSize(1);
        verifyNoInteractions(runner);
    }

    @Test void generatedItemsComeOnlyFromLatestSuccessfulLocalCatalog() {
        var fixture = new WorkflowIntegrationFixture(true);
        var source = source(fixture, new WorkflowCatalogs(fixture.store));
        assertThat(source.item(ID + "." + key)).isEmpty();
        when(runner.catalog(fixture.definition)).thenReturn(List.of(new WorkflowRunner.CatalogEntry(key, "News", null, null)));
        var item = source.rail(ID).items().getFirst();
        assertThat(item.id()).isEqualTo(ID + "." + key);
        assertThat(item.playables()).containsExactly(new PlayableRef.WorkflowCast(ID, 1, key));
        assertThat(source.item(item.id())).contains(item);
        verify(runner, times(1)).catalog(fixture.definition);
        verify(runner, never()).resolve(any(), any());
        when(runner.catalog(fixture.definition)).thenThrow(new WorkflowException(WorkflowException.Stage.FETCH, "failed"));
        assertThatThrownBy(() -> source.rail(ID)).isInstanceOf(ContentSourceException.class);
        assertThat(source.item(item.id())).contains(item);
        fixture.store.update(ID, 1, fixture.definition.draft(), fixture.request);
        assertThat(source.item(item.id())).isEmpty();
    }

    @Test void disabledSourceAndWorkflowCannotExposeOrLoadItems() {
        var fixture = new WorkflowIntegrationFixture(false);
        var source = source(fixture, new WorkflowCatalogs(fixture.store));
        when(preferences.sourceEnabled("workflows")).thenReturn(false);
        assertThat(source.available()).isFalse();
        assertThat(source.rails()).isEmpty();
        assertThat(source.item(ID)).isEmpty();
        assertThatThrownBy(() -> source.rail(ID)).isInstanceOf(ContentSourceException.class);
        when(preferences.sourceEnabled("workflows")).thenReturn(true);
        fixture.store.setEnabled(ID, 1, false, fixture.request);
        assertThat(source.available()).isFalse();
        assertThat(source.item(ID)).isEmpty();
        verifyNoInteractions(runner);
    }

    @Test void laterStartedRefreshWinsWithinSameRevision() throws Exception {
        var fixture = new WorkflowIntegrationFixture(true);
        var catalogs = new WorkflowCatalogs(fixture.store);
        var source = source(fixture, catalogs);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(runner.catalog(fixture.definition)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return List.of(new WorkflowRunner.CatalogEntry(key, "Old", null, null));
        }).thenReturn(List.of(new WorkflowRunner.CatalogEntry(key, "New", null, null)));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = workers.submit(() -> source.rail(ID));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(source.rail(ID).items().getFirst().title()).isEqualTo("New");
            } finally { release.countDown(); }
            assertThatThrownBy(() -> old.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ContentSourceException.class);
        }
        assertThat(source.item(ID + "." + key).orElseThrow().title()).isEqualTo("New");
    }

    @Test void editsAndDeletesRetireInFlightLoadsWithoutReturningOldRails() throws Exception {
        for (boolean delete : List.of(false, true)) {
            var fixture = new WorkflowIntegrationFixture(true);
            var catalogs = new WorkflowCatalogs(fixture.store);
            var source = source(fixture, catalogs);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(runner.catalog(fixture.definition)).thenAnswer(call -> {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return List.of(new WorkflowRunner.CatalogEntry(key, "Old", null, null));
            });
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var old = workers.submit(() -> source.rail(ID));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    if (delete) fixture.store.remove(ID, 1, fixture.request);
                    else fixture.store.update(ID, 1, fixture.definition.draft(), fixture.request);
                    // Event delivery may lag behind the edit: publication must independently check the store.
                } finally { release.countDown(); }
                assertThatThrownBy(() -> old.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ContentSourceException.class);
            }
            assertThat(catalogs.find(ID + "." + key)).isEmpty();
        }
    }

    @Test void invalidationRetiresGenerationEvenWhenRevisionDidNotChange() {
        var fixture = new WorkflowIntegrationFixture(true);
        var catalogs = new WorkflowCatalogs(fixture.store);
        long old = catalogs.begin(ID);
        catalogs.invalidate();
        long next = catalogs.begin(ID);
        assertThat(next).isGreaterThan(old);
        assertThat(catalogs.publish(fixture.definition, old, List.of(new WorkflowRunner.CatalogEntry(key, "Old", null, null)))).isFalse();
        assertThat(catalogs.publish(fixture.definition, next, List.of(new WorkflowRunner.CatalogEntry(key, "New", null, null)))).isTrue();
        assertThat(catalogs.find(ID + "." + key).orElseThrow().title()).isEqualTo("New");
    }
}
