package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.content.ContentChangedEvent;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "home-control.workflows.enabled", havingValue = "true", matchIfMissing = true)
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

    @Bean public WorkflowRunner workflowRunner(WorkflowHttpClient http) { return new WorkflowRunner(http); }
    @Bean public WorkflowCatalogs workflowCatalogs(WorkflowStore store) { return new WorkflowCatalogs(store); }

    @Bean public WorkflowContentSource workflowContentSource(WorkflowStore store, WorkflowRunner runner,
                                                             WorkflowCatalogs catalogs, RailPreferences preferences) {
        return new WorkflowContentSource(store, runner, catalogs, preferences);
    }

    @Bean public WorkflowCastRouteExecutor workflowCastRouteExecutor(WorkflowStore store, WorkflowRunner runner,
                                                                      DeviceManager devices, RailPreferences preferences) {
        return new WorkflowCastRouteExecutor(store, runner, devices, preferences);
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
