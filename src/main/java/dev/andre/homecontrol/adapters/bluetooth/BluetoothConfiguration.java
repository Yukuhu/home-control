package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.MpvLauncher;
import dev.andre.homecontrol.adapters.bluetooth.player.ProcessMpvLauncher;
import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The Bluetooth speaker module, off unless {@code home-control.bluetooth.enabled=true}. Only this
 * configuration's bean methods may construct the D-Bus client, so a disabled module never loads
 * a D-Bus class.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.BLUETOOTH)
@EnableConfigurationProperties(BluetoothProperties.class)
public class BluetoothConfiguration {

    @Bean(destroyMethod = "close")
    public BluezClient bluezClient(BluetoothProperties properties) {
        // The only reference to the D-Bus implementation class in the whole codebase: importing it
        // here as a return type would defeat the point, so it is named only inside this method body.
        return new dev.andre.homecontrol.adapters.bluetooth.bluez.DbusBluezClient(properties.dbusAddress(),
                properties.dbusSocketPath(), properties.bluezTimeout());
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
    public BluetoothPairingService bluetoothPairingService(BluezClient bluez, DeviceQueries devices,
                                                           DeviceEnrollment enrollment,
                                                           BluetoothProperties properties,
                                                           BluetoothSpeakerAdapter speakers) {
        return new BluetoothPairingService(bluez, devices, enrollment, properties, speakers);
    }

    @Bean
    public BluetoothHostChecks bluetoothHostChecks(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher) {
        return new BluetoothHostChecks(properties, bluez, launcher, Clock.systemUTC());
    }
}
