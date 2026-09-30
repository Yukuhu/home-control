package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.SetupSection;
import dev.andre.homecontrol.core.DeviceQueries;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The Bluetooth speakers section of the setup page. */
@Component
@ConditionalOnModule(Module.BLUETOOTH)
public class BluetoothSetupSection implements SetupSection {

    public record SpeakerRow(String id, String name, String address, String status, String audioDevice) {
    }

    public record View(List<HostCheck> checks, boolean hostReady, BluetoothScan scan, int scanSeconds,
                       List<SpeakerRow> speakers, Set<String> registeredAddresses) {
    }

    private final ObjectProvider<BluetoothHostChecks> checks;
    private final ObjectProvider<BluetoothPairingService> pairing;
    private final ObjectProvider<DeviceQueries> devices;
    private final ObjectProvider<BluetoothProperties> properties;

    public BluetoothSetupSection(ObjectProvider<BluetoothHostChecks> checks, ObjectProvider<BluetoothPairingService> pairing,
                                ObjectProvider<DeviceQueries> devices, ObjectProvider<BluetoothProperties> properties) {
        this.checks = checks;
        this.pairing = pairing;
        this.devices = devices;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "bluetooth";
    }

    @Override
    public String title() {
        return "Bluetooth speakers";
    }

    @Override
    public String fragment() {
        return "fragments/bluetooth-setup";
    }

    @Override
    public Group group() {
        return Group.DEVICES;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public View view(URI baseUrl) {
        BluetoothHostChecks hostChecks = checks.getIfAvailable();
        BluetoothPairingService service = pairing.getIfAvailable();
        DeviceQueries registered = devices.getIfAvailable();
        BluetoothProperties props = properties.getIfAvailable();
        if (hostChecks == null || service == null || registered == null || props == null) {
            return null;
        }
        List<HostCheck> results = hostChecks.results();
        List<SpeakerRow> speakers = registered.devices().stream()
                .filter(device -> device.hasAdapter(BluetoothSettings.ADAPTER_ID))
                .map(device -> {
                    BluetoothSettings settings = BluetoothSettings.of(device);
                    return new SpeakerRow(device.id(), device.name(), settings.address(),
                            registered.state(device.id()).status().name(), settings.audioDevice());
                })
                .toList();
        return new View(results, results.stream().allMatch(HostCheck::ok), service.lastScan(),
                Math.toIntExact(props.scanDuration().toSeconds()),
                speakers, speakers.stream().map(SpeakerRow::address).collect(Collectors.toSet()));
    }
}
