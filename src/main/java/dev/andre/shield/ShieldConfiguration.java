package dev.andre.shield;

import dev.andre.shield.device.DeviceRegistry;
import dev.andre.shield.device.JsonFileDeviceRegistry;
import dev.andre.shield.protocol.CertificateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShieldConfiguration {

    @Bean
    public DeviceRegistry deviceRegistry(ShieldProperties properties) {
        return new JsonFileDeviceRegistry(properties.devicesFile());
    }

    @Bean
    public CertificateStore certificateStore(ShieldProperties properties) {
        return new CertificateStore(properties.keystoreFile(),
                properties.keystorePassword().toCharArray());
    }
}
