package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Cast module. {@code home-control.cast.enabled=false} removes discovery and the adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.CAST)
@EnableConfigurationProperties(CastProperties.class)
public class CastConfiguration {

    @Bean
    public CastDiscovery castDiscovery(MdnsBrowser browser, ApplicationEventPublisher events) {
        return new CastDiscovery(browser, events);
    }

    @Bean
    public CastAdapter castAdapter(CastDiscovery discovery, CastProperties properties) {
        return new CastAdapter(discovery, properties);
    }
}
