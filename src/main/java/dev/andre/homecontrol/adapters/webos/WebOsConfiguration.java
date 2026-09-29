package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The LG webOS module; {@code home-control.webos.enabled=false} removes it entirely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.WEBOS)
@EnableConfigurationProperties(WebOsProperties.class)
public class WebOsConfiguration {

    @Bean
    public WebOsAdapter webOsAdapter(WebOsProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry,
                                     WakeOnLan wakeOnLan, DeviceSecrets secrets) {
        return new WebOsAdapter(properties, ssdp, registry, wakeOnLan, secrets);
    }

    @Bean
    public WebOsPairing webOsPairing(WebOsProperties properties, SsdpDiscovery ssdp, DeviceManager devices,
                                     DeviceSecrets secrets) {
        return new WebOsPairing(properties, ssdp, devices, secrets);
    }
}
