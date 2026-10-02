package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/** The one Wake-on-LAN sender the TV modules share, aimed at the configured broadcast address and port. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WakeOnLanProperties.class)
public class WakeOnLanConfiguration {

    @Bean
    public WakeOnLan wakeOnLan(WakeOnLanProperties properties) {
        return new WakeOnLan(new InetSocketAddress(properties.broadcastAddress(), properties.port()));
    }
}
