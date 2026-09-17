package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.ProcessMpvLauncher;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * The Bluetooth speaker module, off unless {@code home-control.bluetooth.enabled=true}. Only this
 * configuration's bean methods may construct the D-Bus client, so a disabled module never loads
 * a D-Bus class.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BluetoothProperties.class)
public class BluetoothConfiguration {

    @Bean(destroyMethod = "close")
    public BluezClient bluezClient(BluetoothProperties properties) {
        // The only reference to the D-Bus implementation class in the whole codebase: importing it
        // here as a return type would defeat the point, so it is named only inside this method body.
        return new dev.andre.homecontrol.adapters.bluetooth.bluez.DbusBluezClient(properties.dbusAddress(),
                properties.dbusSocketPath(), Duration.ofSeconds(properties.bluezTimeoutSeconds()));
    }

    @Bean(destroyMethod = "close")
    public MpvLauncher mpvLauncher(BluetoothProperties properties) {
        return new ProcessMpvLauncher(properties.mpvPath());
    }

    @Bean
    public BluetoothSpeakerAdapter bluetoothSpeakerAdapter(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher) {
        return new BluetoothSpeakerAdapter(properties, bluez, launcher);
    }

    @Bean
    public BluetoothPairingService bluetoothPairingService(BluezClient bluez, DeviceManager devices, BluetoothProperties properties) {
        return new BluetoothPairingService(bluez, devices, properties);
    }

    @Bean
    public BluetoothHostChecks bluetoothHostChecks(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher) {
        return new BluetoothHostChecks(properties, bluez, launcher, Clock.systemUTC());
    }
}
