package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The host's BlueZ, as the Bluetooth module needs it. Addresses are MACs in {@code AA:BB:CC:DD:EE:FF}
 * form; the adapter is identified by its MAC. Implementations must not do I/O in their constructor.
 */
public interface BluezClient extends AutoCloseable {

    List<BluetoothAdapterInfo> adapters() throws BluezException;

    void powerOn(String adapterAddress) throws BluezException;

    /** Runs discovery for {@code duration}, stops it, then lists every device the adapter knows. */
    List<BluetoothDeviceInfo> discover(String adapterAddress, Duration duration) throws BluezException;

    List<BluetoothDeviceInfo> devices(String adapterAddress) throws BluezException;

    Optional<BluetoothDeviceInfo> device(String adapterAddress, String address) throws BluezException;

    /** Pairs; an already paired device is not an error. */
    void pair(String adapterAddress, String address) throws BluezException;

    void trust(String adapterAddress, String address) throws BluezException;

    /** Connects; an already connected device is not an error. */
    void connect(String adapterAddress, String address) throws BluezException;

    /** Disconnects; a disconnected device is not an error. */
    void disconnect(String adapterAddress, String address) throws BluezException;

    /** Unpairs and removes the device from the adapter; an unknown device is not an error. */
    void remove(String adapterAddress, String address) throws BluezException;

    @Override
    void close();
}
