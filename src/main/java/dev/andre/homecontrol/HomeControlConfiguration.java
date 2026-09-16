package dev.andre.homecontrol;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.storage.DataDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HomeControlConfiguration {

    @Bean
    public DeviceRegistry deviceRegistry(AndroidTvProperties properties) {
        return new JsonFileDeviceRegistry(properties.devicesFile());
    }

    @Bean
    public CertificateStore certificateStore(AndroidTvProperties properties) {
        return new CertificateStore(properties.keystoreFile(),
                properties.keystorePassword().toCharArray());
    }

    @Bean
    public DataDirectory dataDirectory(AndroidTvProperties properties) {
        return new DataDirectory(properties.dataDir());
    }
}
