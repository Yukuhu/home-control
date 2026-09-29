package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.core.playback.RefStrategy;
import dev.andre.homecontrol.core.playback.RouteStrategy;
import dev.andre.homecontrol.core.playback.Rung;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import java.net.InetAddress;
import java.security.SecureRandom;

/** The complete workflow runtime disappears when its server module is disabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.WORKFLOWS)
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfiguration {
    @Bean public WorkflowCodec workflowCodec() { return new WorkflowCodec(); }

    @Bean public WorkflowStore workflowStore(SecretStore secrets, LoginService login, WorkflowCodec codec,
                                             ApplicationEventPublisher events) {
        return new WorkflowStore(secrets, login, codec, events, new SecureRandom());
    }

    @Bean public WorkflowUrlPolicy workflowUrlPolicy(WorkflowProperties properties) {
        return new WorkflowUrlPolicy(properties.allowLoopback(), InetAddress::getAllByName);
    }

    @Bean(destroyMethod = "close")
    public WorkflowHttpClient workflowHttpClient(WorkflowProperties properties, WorkflowUrlPolicy policy) {
        return new WorkflowHttpClient(properties, policy);
    }

    @Bean public WorkflowRunner workflowRunner(WorkflowHttpClient http, WorkflowProperties properties) {
        return new WorkflowRunner(http, properties);
    }
    @Bean public WorkflowCatalogs workflowCatalogs(WorkflowStore store) { return new WorkflowCatalogs(store); }

    @Bean public WorkflowContentSource workflowContentSource(WorkflowStore store, WorkflowRunner runner,
                                                             WorkflowCatalogs catalogs, RailPreferences preferences) {
        return new WorkflowContentSource(store, runner, catalogs, preferences);
    }

    @Bean public WorkflowCastRouteExecutor workflowCastRouteExecutor(WorkflowStore store, WorkflowRunner runner,
                                                                      DeviceQueries devices, DeviceCommands commands,
                                                                      RailPreferences preferences) {
        return new WorkflowCastRouteExecutor(store, runner, devices, commands, preferences);
    }

    /** Pure planning: credentials and media addresses are resolved only by the route executor. */
    @Bean public RouteStrategy workflowCastStrategy() {
        return RefStrategy.of(Rung.CAST_APP, Capability.CAST_RECEIVER, WorkflowCastRef.class,
                (ref, item) -> new WorkflowCastRoute(ref.workflowId(), ref.revision(), ref.entryKey()));
    }

    @Bean public ContentChanges workflowContentChanges(WorkflowCatalogs catalogs, ObjectProvider<RailCache> rails) {
        return new ContentChanges(catalogs, rails);
    }

    public static final class ContentChanges {
        private final WorkflowCatalogs catalogs;
        private final ObjectProvider<RailCache> rails;

        ContentChanges(WorkflowCatalogs catalogs, ObjectProvider<RailCache> rails) {
            this.catalogs = catalogs;
            this.rails = rails;
        }

        @EventListener
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public void changed(ContentChangedEvent event) {
            if (!WorkflowContentSource.SOURCE_ID.equals(event.sourceId())) return;
            catalogs.invalidate();
            // Resolve only on the event: source/cache construction otherwise forms a dependency cycle.
            RailCache cache = rails.getIfAvailable();
            if (cache != null) cache.invalidateSource(WorkflowContentSource.SOURCE_ID);
        }
    }
}
