package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Samsung Tizen module; {@code home-control.tizen.enabled=false} removes it entirely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.TIZEN)
@EnableConfigurationProperties(TizenProperties.class)
public class TizenConfiguration {

    @Bean
    public TizenAdapter tizenAdapter(TizenProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry,
                                     WakeOnLan wakeOnLan, DeviceSecrets secrets) {
        return new TizenAdapter(properties, ssdp, registry, wakeOnLan, secrets);
    }

    @Bean
    public TizenPairing tizenPairing(TizenProperties properties, DeviceQueries devices, DeviceEnrollment enrollment,
                                     DeviceSecrets secrets) {
        return new TizenPairing(properties, devices, enrollment, secrets);
    }
}
