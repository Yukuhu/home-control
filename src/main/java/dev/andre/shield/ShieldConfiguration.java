package dev.andre.shield;

import dev.andre.shield.device.DeviceRegistry;
import dev.andre.shield.device.JsonFileDeviceRegistry;
import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.storage.DataDirectory;
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

    @Bean
    public DataDirectory dataDirectory(ShieldProperties properties) {
        return new DataDirectory(properties.dataDir());
    }
}
