package dev.andre.homecontrol;

import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.Devices;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The device beans: one {@link Devices}, started and closed with the context, and its four interfaces. */
class HomeControlConfigurationTest {

    @TempDir
    Path dir;

    @Test
    void theFourDeviceBeansArePartsOfOneDevices() {
        HomeControlConfiguration configuration = new HomeControlConfiguration();
        try (Devices devices = configuration.devices(new JsonFileDeviceRegistry(dir.resolve("devices.json")),
                List.of(), event -> { })) {
            assertThat(configuration.deviceQueries(devices)).isSameAs(devices.queries());
            assertThat(configuration.deviceCommands(devices)).isSameAs(devices.commands());
            assertThat(configuration.deviceEnrollment(devices)).isSameAs(devices.enrollment());
            assertThat(configuration.deviceSettings(devices)).isSameAs(devices.settings());
        }
    }

    @Test
    void devicesStartWithTheContextAndCloseWithIt() throws Exception {
        Bean bean = HomeControlConfiguration.class.getMethod("devices", DeviceRegistry.class, List.class,
                ApplicationEventPublisher.class).getAnnotation(Bean.class);

        assertThat(bean.initMethod()).isEqualTo("start");
        assertThat(bean.destroyMethod()).isEqualTo("close");
    }
}
