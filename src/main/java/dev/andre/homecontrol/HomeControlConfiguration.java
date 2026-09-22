package dev.andre.homecontrol;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.AppLinkStrategy;
import dev.andre.homecontrol.core.playback.CastLoadStrategy;
import dev.andre.homecontrol.core.playback.CastMessageStrategy;
import dev.andre.homecontrol.core.playback.CastStreamStrategy;
import dev.andre.homecontrol.core.playback.JellyfinSessionStrategy;
import dev.andre.homecontrol.core.playback.LocalAudioSinkStrategy;
import dev.andre.homecontrol.core.playback.MediaRendererStrategy;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.YouTubeLoungeStrategy;
import dev.andre.homecontrol.core.playback.WorkflowCastStrategy;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.playback.DeepLinkTestProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(DeepLinkTestProperties.class)
public class HomeControlConfiguration {

    @Bean
    public DeviceRegistry deviceRegistry(AndroidTvProperties properties) {
        return new JsonFileDeviceRegistry(properties.devicesFile());
    }

    @Bean
    public PlaybackPlanner playbackPlanner() {
        // Preference order of spec §5.3: an open Jellyfin app, an app link, Cast (a video through the
        // receiver's best-effort remote pairing, custom-message receivers, then LOADs, then bare streams
        // on the Default Media Receiver), then media renderers (DLNA/UPnP/Sonos), then the server's own
        // player for local audio sinks (Bluetooth).
        return new PlaybackPlanner(List.of(new JellyfinSessionStrategy(), new AppLinkStrategy(), new WorkflowCastStrategy(),
                new YouTubeLoungeStrategy(), new CastMessageStrategy(), new CastLoadStrategy(),
                new CastStreamStrategy(), new MediaRendererStrategy(), new LocalAudioSinkStrategy()));
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

    @Bean
    public JsonFileSourceSettings sourceSettings(AndroidTvProperties properties) {
        return new JsonFileSourceSettings(properties.dataDir().resolve("sources.json"));
    }

    /** Every content source found in the context, in bean order (spec §5.2, §7). */
    @Bean
    public ContentSources contentSources(ObjectProvider<ContentSource> sources) {
        return new ContentSources(sources.orderedStream().toList());
    }
}
