package dev.andre.homecontrol.adapters.bluetooth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The Bluetooth speaker module, off unless {@code home-control.bluetooth.enabled=true}. Only this
 * configuration's bean methods may construct the D-Bus client, so a disabled module never loads
 * a D-Bus class.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BluetoothProperties.class)
public class BluetoothConfiguration {
}
