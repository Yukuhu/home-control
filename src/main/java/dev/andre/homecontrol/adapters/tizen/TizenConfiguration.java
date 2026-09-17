package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Samsung Tizen module; {@code home-control.tizen.enabled=false} removes it entirely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.tizen", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TizenProperties.class)
public class TizenConfiguration {

    @Bean
    public TizenAdapter tizenAdapter(TizenProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry,
                                     WakeOnLan wakeOnLan) {
        return new TizenAdapter(properties, ssdp, registry, wakeOnLan);
    }

    @Bean
    public TizenPairing tizenPairing(TizenProperties properties, DeviceManager devices) {
        return new TizenPairing(properties, devices);
    }
}
