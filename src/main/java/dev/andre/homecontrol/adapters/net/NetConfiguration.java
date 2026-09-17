package dev.andre.homecontrol.adapters.net;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/** Network helpers shared by the TV modules. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WakeOnLanProperties.class)
public class NetConfiguration {

    @Bean
    public WakeOnLan wakeOnLan(WakeOnLanProperties properties) {
        return new WakeOnLan(new InetSocketAddress(properties.broadcastAddress(), properties.port()));
    }
}
