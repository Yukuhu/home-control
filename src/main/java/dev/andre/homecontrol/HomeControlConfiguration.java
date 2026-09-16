package dev.andre.homecontrol;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.playback.AppLinkStrategy;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.storage.DataDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class HomeControlConfiguration {

    @Bean
    public DeviceRegistry deviceRegistry(AndroidTvProperties properties) {
        return new JsonFileDeviceRegistry(properties.devicesFile());
    }

    @Bean
    public PlaybackPlanner playbackPlanner() {
        // Strategy order is the preference order of spec §5.3; later sub-projects insert theirs.
        return new PlaybackPlanner(List.of(new AppLinkStrategy()));
    }

    /** The one mDNS browser every adapter's discovery shares (spec §7). */
    @Bean
    public MdnsBrowser mdnsBrowser(AndroidTvProperties properties) {
        return new MdnsBrowser(properties.discoveryEnabled());
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
