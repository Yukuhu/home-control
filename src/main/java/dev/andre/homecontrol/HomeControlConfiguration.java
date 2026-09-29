package dev.andre.homecontrol;

import dev.andre.homecontrol.config.HomeControlProperties;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceSettings;
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
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.device.Devices;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.playback.DeepLinkTestProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
@EnableConfigurationProperties(DeepLinkTestProperties.class)
public class HomeControlConfiguration {

    @Bean
    public DeviceRegistry deviceRegistry(DataDirectory data) {
        return new JsonFileDeviceRegistry(data.resolve(DataDirectory.DEVICES));
    }

    /**
     * The device collaborators (spec 3A): started once the registry and every switched-on adapter exist, before
     * discovery starts on application-ready, and closed with the context.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public Devices devices(DeviceRegistry registry, List<DeviceAdapter> adapters, ApplicationEventPublisher events) {
        return Devices.assemble(registry, adapters, events);
    }

    // @Primary until DeviceManager, which implements the four interfaces too, is gone (3A PR 2, Task 8).
    @Bean
    @Primary
    public DeviceQueries deviceQueries(Devices devices) {
        return devices.queries();
    }

    @Bean
    @Primary
    public DeviceCommands deviceCommands(Devices devices) {
        return devices.commands();
    }

    @Bean
    @Primary
    public DeviceEnrollment deviceEnrollment(Devices devices) {
        return devices.enrollment();
    }

    @Bean
    @Primary
    public DeviceSettings deviceSettings(Devices devices) {
        return devices.settings();
    }

    /** For the callers that have not moved onto the four interfaces yet. */
    @Bean
    public DeviceManager deviceManager(Devices devices) {
        return new DeviceManager(devices);
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
    public MdnsBrowser mdnsBrowser(HomeControlProperties properties) {
        return new MdnsBrowser(properties.discovery().enabled());
    }

    @Bean
    public DataDirectory dataDirectory(HomeControlProperties properties) {
        DataDirectory directory = new DataDirectory(properties.dataDir());
        directory.verifyUsable();
        return directory;
    }

    @Bean
    public JsonFileSourceSettings sourceSettings(DataDirectory data) {
        return new JsonFileSourceSettings(data.resolve(DataDirectory.SOURCES));
    }

    /** Every content source found in the context, in bean order (spec §5.2, §7). */
    @Bean
    public ContentSources contentSources(ObjectProvider<ContentSource> sources) {
        return new ContentSources(sources.orderedStream().toList());
    }
}
