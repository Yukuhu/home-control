package dev.andre.homecontrol.adapters.bluetooth.bluez;

import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import org.bluez.Adapter1;
import org.bluez.Device1;
import org.bluez.exceptions.BluezAlreadyConnectedException;
import org.bluez.exceptions.BluezAlreadyExistsException;
import org.bluez.exceptions.BluezInProgressException;
import org.bluez.exceptions.BluezNotConnectedException;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.messages.MethodCall;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BlueZ over the D-Bus system bus via bluez-dbus. Connects on first use and again after the
 * connection broke, so the application starts without D-Bus and the setup page can say why.
 * Never annotate this class: only BluetoothConfiguration may construct it.
 */
public final class DbusBluezClient implements BluezClient {

    private static final Logger log = LoggerFactory.getLogger(DbusBluezClient.class);
    private static final String BLUEZ = "org.bluez";
    private static final String ADAPTER_INTERFACE = "org.bluez.Adapter1";
    private static final String DEVICE_INTERFACE = "org.bluez.Device1";

    private interface DbusCall<T> {
        T run(DBusConnection connection) throws DBusException, BluezException;
    }

    private final String address;
    private final Optional<Path> socket;
    private final Object connectionLock = new Object();
    private DBusConnection connection; // guarded by connectionLock

    public DbusBluezClient(String address, Optional<Path> socket, Duration replyTimeout) {
        this.address = address;
        this.socket = socket;
        // Pair() waits for the speaker; dbus-java's default reply timeout is shorter.
        MethodCall.setDefaultTimeout(replyTimeout.toMillis());
    }

    @Override
    public List<BluetoothAdapterInfo> adapters() throws BluezException {
        return call("list adapters", connection -> {
            List<BluetoothAdapterInfo> adapters = new ArrayList<>();
            managedObjects(connection).forEach((path, interfaces) -> {
                Map<String, Variant<?>> properties = interfaces.get(ADAPTER_INTERFACE);
                if (properties != null) {
                    String objectPath = path.getPath();
                    adapters.add(new BluetoothAdapterInfo(objectPath.substring(objectPath.lastIndexOf('/') + 1),
                            text(properties, "Address"), text(properties, "Alias"), flag(properties, "Powered")));
                }
            });
            adapters.sort(Comparator.comparing(BluetoothAdapterInfo::id));
            return adapters;
        });
    }

    @Override
    public void powerOn(String adapterAddress) throws BluezException {
        call("power on the adapter", connection -> {
            String path = adapterPath(managedObjects(connection), adapterAddress);
            new BluetoothAdapter(connection.getRemoteObject(BLUEZ, path, Adapter1.class), path, connection).setPowered(true);
            return null;
        });
    }

    @Override
    public List<BluetoothDeviceInfo> discover(String adapterAddress, Duration duration) throws BluezException {
        Adapter1 adapter = call("start scanning", connection -> {
            Adapter1 remote = connection.getRemoteObject(BLUEZ, adapterPath(managedObjects(connection), adapterAddress), Adapter1.class);
            try {
                remote.StartDiscovery();
            } catch (BluezInProgressException alreadyScanning) {
                // another client (bluetoothctl, a desktop) is scanning: the results are shared
            }
            return remote;
        });
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                adapter.StopDiscovery();
            } catch (Exception e) {
                log.debug("StopDiscovery failed: {}", e.toString());
            }
        }
        return devices(adapterAddress);
    }

    @Override
    public List<BluetoothDeviceInfo> devices(String adapterAddress) throws BluezException {
        return call("list devices", connection -> {
            Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
            String adapterPath = adapterPath(objects, adapterAddress);
            List<BluetoothDeviceInfo> devices = new ArrayList<>();
            objects.values().forEach(interfaces -> {
                Map<String, Variant<?>> properties = interfaces.get(DEVICE_INTERFACE);
                if (properties != null && adapterPath.equals(objectPath(properties.get("Adapter")))) {
                    devices.add(device(properties));
                }
            });
            return devices;
        });
    }

    @Override
    public Optional<BluetoothDeviceInfo> device(String adapterAddress, String address) throws BluezException {
        return devices(adapterAddress).stream().filter(found -> address.equalsIgnoreCase(found.address())).findFirst();
    }

    @Override
    public void pair(String adapterAddress, String address) throws BluezException {
        call("pair " + address, connection -> {
            try {
                device1(connection, adapterAddress, address).Pair();
            } catch (BluezAlreadyExistsException alreadyPaired) {
                // paired before
            }
            return null;
        });
    }

    @Override
    public void trust(String adapterAddress, String address) throws BluezException {
        call("trust " + address, connection -> {
            Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
            String adapterPath = adapterPath(objects, adapterAddress);
            String devicePath = devicePath(objects, adapterPath, address)
                    .orElseThrow(() -> new BluezException(BluezFailure.NOT_FOUND, BluezFailures.message(BluezFailure.NOT_FOUND, address)));
            BluetoothAdapter adapter = new BluetoothAdapter(connection.getRemoteObject(BLUEZ, adapterPath, Adapter1.class), adapterPath, connection);
            new BluetoothDevice(connection.getRemoteObject(BLUEZ, devicePath, Device1.class), adapter, devicePath, connection).setTrusted(true);
            return null;
        });
    }

    @Override
    public void connect(String adapterAddress, String address) throws BluezException {
        call("connect " + address, connection -> {
            try {
                device1(connection, adapterAddress, address).Connect();
            } catch (BluezAlreadyConnectedException alreadyConnected) {
                // fine
            }
            return null;
        });
    }

    @Override
    public void disconnect(String adapterAddress, String address) throws BluezException {
        call("disconnect " + address, connection -> {
            try {
                device1(connection, adapterAddress, address).Disconnect();
            } catch (BluezNotConnectedException notConnected) {
                // fine
            }
            return null;
        });
    }

    @Override
    public void remove(String adapterAddress, String address) throws BluezException {
        call("remove " + address, connection -> {
            Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
            String adapterPath = adapterPath(objects, adapterAddress);
            Optional<String> devicePath = devicePath(objects, adapterPath, address);
            if (devicePath.isPresent()) {
                connection.getRemoteObject(BLUEZ, adapterPath, Adapter1.class).RemoveDevice(new DBusPath(devicePath.get()));
            }
            return null;
        });
    }

    @Override
    public void close() {
        synchronized (connectionLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (IOException e) {
                    log.debug("Closing the D-Bus connection failed: {}", e.toString());
                }
                connection = null;
            }
        }
    }

    private <T> T call(String what, DbusCall<T> action) throws BluezException {
        DBusConnection current = connection();
        try {
            return action.run(current);
        } catch (DBusException | DBusExecutionException e) {
            String name = e instanceof DBusExecutionException execution && execution.getType() != null
                    ? execution.getType() : e.getClass().getName();
            BluezFailure failure = BluezFailures.classify(name, e.getMessage());
            if (!current.isConnected()) {
                close();
            }
            throw new BluezException(failure, BluezFailures.message(failure, what + ": " + e.getMessage()), e);
        }
    }

    private DBusConnection connection() throws BluezException {
        synchronized (connectionLock) {
            if (connection != null && connection.isConnected()) {
                return connection;
            }
            connection = null;
            if (socket.isPresent() && !Files.exists(socket.get())) {
                throw new BluezException(BluezFailure.NO_DBUS_SOCKET, BluezFailures.noSocket(socket.get()));
            }
            try {
                connection = DBusConnectionBuilder.forAddress(address).withShared(false).build();
                return connection;
            } catch (DBusException | RuntimeException e) {
                BluezFailure failure = BluezFailures.classify(e.getClass().getName(), e.getMessage());
                throw new BluezException(failure, BluezFailures.message(failure,
                        "could not connect to D-Bus at " + address + ": " + e.getMessage()), e);
            }
        }
    }

    private static Map<DBusPath, Map<String, Map<String, Variant<?>>>> managedObjects(DBusConnection connection) throws DBusException {
        return connection.getRemoteObject(BLUEZ, "/", ObjectManager.class).GetManagedObjects();
    }

    private static String adapterPath(Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects, String adapterAddress)
            throws BluezException {
        for (Map.Entry<DBusPath, Map<String, Map<String, Variant<?>>>> entry : objects.entrySet()) {
            Map<String, Variant<?>> properties = entry.getValue().get(ADAPTER_INTERFACE);
            if (properties != null && adapterAddress.equalsIgnoreCase(text(properties, "Address"))) {
                return entry.getKey().getPath();
            }
        }
        throw new BluezException(BluezFailure.NO_ADAPTER, BluezFailures.message(BluezFailure.NO_ADAPTER, "no adapter " + adapterAddress));
    }

    private static Optional<String> devicePath(Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects, String adapterPath,
                                               String address) {
        return objects.entrySet().stream()
                .filter(entry -> {
                    Map<String, Variant<?>> properties = entry.getValue().get(DEVICE_INTERFACE);
                    return properties != null && address.equalsIgnoreCase(text(properties, "Address"))
                            && adapterPath.equals(objectPath(properties.get("Adapter")));
                })
                .map(entry -> entry.getKey().getPath())
                .findFirst();
    }

    private static Device1 device1(DBusConnection connection, String adapterAddress, String address)
            throws DBusException, BluezException {
        Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
        String path = devicePath(objects, adapterPath(objects, adapterAddress), address)
                .orElseThrow(() -> new BluezException(BluezFailure.NOT_FOUND, BluezFailures.message(BluezFailure.NOT_FOUND, address)));
        return connection.getRemoteObject(BLUEZ, path, Device1.class);
    }

    private static BluetoothDeviceInfo device(Map<String, Variant<?>> properties) {
        String address = text(properties, "Address");
        String name = text(properties, "Name");
        if (name == null || name.isBlank()) {
            String alias = text(properties, "Alias");
            name = alias != null && !alias.replace('-', ':').equalsIgnoreCase(address) ? alias : null;
        }
        Variant<?> rssi = properties.get("RSSI");
        return new BluetoothDeviceInfo(address, name, text(properties, "Icon"), flag(properties, "Paired"),
                flag(properties, "Trusted"), flag(properties, "Connected"), strings(properties.get("UUIDs")),
                rssi != null && rssi.getValue() instanceof Number number ? number.shortValue() : null);
    }

    private static String text(Map<String, Variant<?>> properties, String name) {
        Variant<?> value = properties.get(name);
        return value == null || value.getValue() == null ? null : String.valueOf(value.getValue());
    }

    private static boolean flag(Map<String, Variant<?>> properties, String name) {
        Variant<?> value = properties.get(name);
        return value != null && Boolean.TRUE.equals(value.getValue());
    }

    private static List<String> strings(Variant<?> value) {
        if (value == null) {
            return List.of();
        }
        Object raw = value.getValue();
        if (raw instanceof String[] array) {
            return List.of(array);
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String objectPath(Variant<?> value) {
        if (value == null || value.getValue() == null) {
            return null;
        }
        return value.getValue() instanceof DBusPath path ? path.getPath() : String.valueOf(value.getValue());
    }
}
