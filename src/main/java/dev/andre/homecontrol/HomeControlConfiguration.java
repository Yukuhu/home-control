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
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import dev.andre.homecontrol.core.playback.RouteStrategy;
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

    @Bean
    public DeviceQueries deviceQueries(Devices devices) {
        return devices.queries();
    }

    @Bean
    public DeviceCommands deviceCommands(Devices devices) {
        return devices.commands();
    }

    @Bean
    public DeviceEnrollment deviceEnrollment(Devices devices) {
        return devices.enrollment();
    }

    @Bean
    public DeviceSettings deviceSettings(Devices devices) {
        return devices.settings();
    }

    @Bean
    public PlaybackPlanner playbackPlanner(List<RouteStrategy> strategies) {
        // The planner sorts every strategy bean onto the preference ladder of spec §5.3 (Rung).
        return new PlaybackPlanner(strategies);
    }

    @Bean
    public RouteStrategy appLinkStrategy() {
        return RouteStrategies.appLink();
    }

    @Bean
    public RouteStrategy castMessageStrategy() {
        return RouteStrategies.castMessage();
    }

    @Bean
    public RouteStrategy castLoadStrategy() {
        return RouteStrategies.castLoad();
    }

    @Bean
    public RouteStrategy castStreamStrategy() {
        return RouteStrategies.castStream();
    }

    @Bean
    public RouteStrategy rendererStrategy() {
        return RouteStrategies.renderer();
    }

    @Bean
    public RouteStrategy localSinkStrategy() {
        return RouteStrategies.localSink();
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
