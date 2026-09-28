package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.storage.DataDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

/** The pinned-shortcuts module. {@code home-control.pinned.enabled=false} removes all of it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.PINNED)
@EnableConfigurationProperties(PinnedProperties.class)
public class PinnedConfiguration {

    @Bean
    public JsonFilePinStore jsonFilePinStore(DataDirectory data) {
        return new JsonFilePinStore(data.resolve(DataDirectory.PINNED));
    }

    /** Also satisfies {@code ObjectProvider<PinnedLinks>} injection points: the interface is implemented here. */
    @Bean
    public PinnedShortcuts pinnedShortcuts(JsonFilePinStore store, PinnedProperties properties,
                                           ApplicationEventPublisher events, ObjectProvider<ContentSources> sources) {
        return new PinnedShortcuts(store, properties, events, Clock.systemUTC(), new SecureRandom(), sources);
    }

    @Bean
    public PinnedContentSource pinnedContentSource(PinnedShortcuts pins) {
        return new PinnedContentSource(pins);
    }
}
