package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Adds the {@code bluetooth} model attribute to every {@link SetupController} response. */
@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")
public class BluetoothSetupAdvice {

    public record SpeakerRow(String id, String name, String address, String status, String audioDevice) {
    }

    public record View(List<HostCheck> checks, boolean hostReady, BluetoothScan scan, int scanSeconds,
                       List<SpeakerRow> speakers, Set<String> registeredAddresses) {
    }

    private final ObjectProvider<BluetoothHostChecks> checks;
    private final ObjectProvider<BluetoothPairingService> pairing;
    private final ObjectProvider<DeviceManager> devices;
    private final ObjectProvider<BluetoothProperties> properties;

    public BluetoothSetupAdvice(ObjectProvider<BluetoothHostChecks> checks, ObjectProvider<BluetoothPairingService> pairing,
                                ObjectProvider<DeviceManager> devices, ObjectProvider<BluetoothProperties> properties) {
        this.checks = checks;
        this.pairing = pairing;
        this.devices = devices;
        this.properties = properties;
    }

    @ModelAttribute("bluetooth")
    public View bluetooth() {
        BluetoothHostChecks hostChecks = checks.getIfAvailable();
        BluetoothPairingService service = pairing.getIfAvailable();
        DeviceManager manager = devices.getIfAvailable();
        BluetoothProperties props = properties.getIfAvailable();
        if (hostChecks == null || service == null || manager == null || props == null) {
            return null;
        }
        List<HostCheck> results = hostChecks.results();
        List<SpeakerRow> speakers = manager.devices().stream()
                .filter(device -> device.hasAdapter(BluetoothSettings.ADAPTER_ID))
                .map(device -> {
                    BluetoothSettings settings = BluetoothSettings.of(device);
                    return new SpeakerRow(device.id(), device.name(), settings.address(),
                            manager.state(device.id()).status().name(), settings.audioDevice());
                })
                .toList();
        return new View(results, results.stream().allMatch(HostCheck::ok), service.lastScan(), props.scanSeconds(),
                speakers, speakers.stream().map(SpeakerRow::address).collect(Collectors.toSet()));
    }
}
