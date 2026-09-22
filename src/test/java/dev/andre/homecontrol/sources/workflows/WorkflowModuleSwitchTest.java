package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.content.ContentProperties;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowModuleSwitchTest {
    private final SecretStore secrets = mock(SecretStore.class);
    private final RailPreferences preferences = mock(RailPreferences.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(SecretStore.class, () -> secrets)
            .withBean(LoginService.class, () -> mock(LoginService.class))
            .withBean(DeviceManager.class, () -> mock(DeviceManager.class))
            .withBean(RailPreferences.class, () -> preferences)
            .withUserConfiguration(WorkflowConfiguration.class);

    @Test void disabledModuleDoesNotReadDefinitionsOrCreateExecutionBeans() {
        context.withPropertyValues("home-control.workflows.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean(WorkflowStore.class).doesNotHaveBean(WorkflowContentSource.class)
                    .doesNotHaveBean(WorkflowRunner.class).doesNotHaveBean(WorkflowHttpClient.class)
                    .doesNotHaveBean(WorkflowCastRouteExecutor.class);
            verifyNoInteractions(secrets);
        });
    }

    @Test void defaultsEnableModuleWithoutLoopbackAndClientClosesWithContext() {
        var client = new AtomicReference<WorkflowHttpClient>();
        context.run(app -> {
            assertThat(app).hasSingleBean(WorkflowStore.class).hasSingleBean(WorkflowContentSource.class)
                    .hasSingleBean(WorkflowCastRouteExecutor.class);
            assertThat(app.getBean(WorkflowProperties.class).allowLoopback()).isFalse();
            assertThat(app.getBean(WorkflowContentSource.class).available()).isFalse();
            client.set(app.getBean(WorkflowHttpClient.class));
        });
        assertThatThrownBy(() -> client.get().fetch(new WorkflowDraft.Fetch("https://never.example", List.of())))
                .isInstanceOf(WorkflowException.class).hasMessageContaining("closed");
    }

    @Test void contentChangeInvalidatesCatalogAndRailBeforeNormalListenerAndAvoidsConstructionCycle() {
        var definition = new WorkflowDefinition(1, WorkflowIntegrationFixture.ID, 1,
                WorkflowFixtures.generated());
        when(secrets.names()).thenReturn(Set.of("workflow." + definition.id()));
        when(secrets.secret("workflow." + definition.id())).thenReturn(Optional.of(new WorkflowCodec().encode(definition)));
        when(preferences.sourceEnabled("workflows")).thenReturn(true);
        when(preferences.rails(any())).thenAnswer(call -> call.<List<ContentSource>>getArgument(0).stream()
                .flatMap(source -> source.rails().stream()).toList());
        when(preferences.refreshInterval(any())).thenReturn(Duration.ofMinutes(15));
        context.withUserConfiguration(CacheConfiguration.class).run(app -> {
            var catalogs = app.getBean(WorkflowCatalogs.class);
            var cache = app.getBean(RailCache.class);
            var observer = app.getBean(ChangeObserver.class);
            long generation = catalogs.begin(definition.id());
            String itemId = definition.id() + "." + "a".repeat(64);
            assertThat(catalogs.publish(definition, generation,
                    List.of(new WorkflowRunner.CatalogEntry("a".repeat(64), "Old", null, null)))).isTrue();
            cache.reconcile();
            long version = cache.peek().getFirst().version();
            observer.assertInvalidated = () -> {
                assertThat(catalogs.find(itemId)).isEmpty();
                // RailCache's normal listener can already have recreated entries, but never retain the old version.
                assertThat(cache.peek()).allSatisfy(row -> assertThat(row.version()).isGreaterThan(version));
            };
            app.publishEvent(new ContentChangedEvent("other"));
            assertThat(catalogs.find(itemId)).isPresent();
            app.publishEvent(new ContentChangedEvent("workflows"));
            assertThat(observer.called).isTrue();
            assertThat(catalogs.publish(definition, generation, List.of())).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CacheConfiguration {
        @Bean ContentSources sources(List<ContentSource> sources) { return new ContentSources(sources); }
        @Bean RailCache cache(ContentSources sources, RailPreferences preferences, ApplicationEventPublisher events) {
            var properties = new ContentProperties(new ContentProperties.Rails(false, Duration.ofSeconds(15),
                    Duration.ofMinutes(1), 4, Map.of()), new ContentProperties.Search(Duration.ofSeconds(8)), "de-DE", "DE");
            // A shut-down executor lets the event reconcile metadata without starting any network requests.
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.shutdown();
            return new RailCache(sources, preferences, events, Clock.systemUTC(), properties, executor);
        }
        @Bean ChangeObserver observer() { return new ChangeObserver(); }
    }

    static class ChangeObserver {
        Runnable assertInvalidated;
        boolean called;
        @EventListener public void changed(ContentChangedEvent event) {
            if (!event.sourceId().equals("workflows")) return;
            assertInvalidated.run();
            called = true;
        }
    }
}
