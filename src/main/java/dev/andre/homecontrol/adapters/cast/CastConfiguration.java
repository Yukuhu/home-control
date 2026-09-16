package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Cast module. {@code home-control.cast.enabled=false} removes discovery and the adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.cast", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CastConfiguration {

    @Bean
    public CastDiscovery castDiscovery(MdnsBrowser browser, ApplicationEventPublisher events) {
        return new CastDiscovery(browser, events);
    }

    @Bean
    public CastAdapter castAdapter(CastDiscovery discovery) {
        return new CastAdapter(discovery);
    }
}
