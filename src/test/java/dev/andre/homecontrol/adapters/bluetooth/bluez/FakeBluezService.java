package dev.andre.homecontrol.adapters.bluetooth.bluez;

import org.bluez.Adapter1;
import org.bluez.Device1;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon;
import org.freedesktop.dbus.connections.BusAddress;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.awaitility.Awaitility.await;

/** BlueZ objects exported on a private, in-process D-Bus; never opens the host's system bus. */
public final class FakeBluezService implements ObjectManager, AutoCloseable {
    private final Path socket;
    private final EmbeddedDBusDaemon daemon;
    private final DBusConnection connection;
    private final Map<DBusPath, FakeProperties> objects = new LinkedHashMap<>();

    public FakeBluezService(Path directory) throws DBusException, IOException {
        socket = directory.resolve("bluez.sock");
        daemon = new EmbeddedDBusDaemon(BusAddress.of(address()).getListenerAddress());
        DBusConnection opened = null;
        try {
            daemon.startInBackgroundAndWait(5_000);
            // dbus-java reports the daemon started once its socket is bound, but its accept loop stops after the first
            // connection when the daemon thread is not running yet, and that connection breaks. Wait for the thread.
            await().atMost(Duration.ofSeconds(5)).until(daemon::isRunning);
            opened = DBusConnectionBuilder.forAddress(address()).withShared(false).build();
            opened.exportObject("/", this);
            opened.requestBusName("org.bluez");
            connection = opened;
        } catch (DBusException | RuntimeException e) {
            if (opened != null) {
                try { opened.close(); }
                catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            try { daemon.close(); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public String address() { return "unix:path=" + socket; }
    public Path socket() { return socket; }

    public synchronized FakeAdapter adapter(String id, String address, String alias, boolean powered) throws DBusException {
        FakeAdapter adapter = new FakeAdapter("/org/bluez/" + id);
        adapter.property("Address", address);
        adapter.property("Alias", alias);
        adapter.property("Powered", powered);
        export(adapter);
        return adapter;
    }

    public synchronized FakeDevice device(FakeAdapter adapter, String address) throws DBusException {
        FakeDevice device = new FakeDevice(adapter.getObjectPath() + "/dev_" + address.replace(':', '_'));
        device.property("Adapter", new DBusPath(adapter.getObjectPath()));
        device.property("Address", address);
        device.property("Paired", false);
        device.property("Trusted", false);
        device.property("Connected", false);
        export(device);
        return device;
    }

    private void export(FakeProperties object) throws DBusException {
        connection.exportObject(object.getObjectPath(), object);
        objects.put(new DBusPath(object.getObjectPath()), object);
    }

    @Override
    public synchronized Map<DBusPath, Map<String, Map<String, Variant<?>>>> GetManagedObjects() {
        Map<DBusPath, Map<String, Map<String, Variant<?>>>> result = new LinkedHashMap<>();
        objects.forEach((path, object) -> result.put(path, Map.of(object.interfaceName, object.GetAll(object.interfaceName))));
        return result;
    }

    @Override public String getObjectPath() { return "/"; }

    @Override
    public void close() throws IOException {
        try { connection.close(); }
        finally { daemon.close(); }
    }

    /** D-Bus Properties is kept real so the bluez-dbus wrappers also exercise their wire calls. */
    public abstract static class FakeProperties implements Properties {
        private final String path;
        private final String interfaceName;
        private final Map<String, Variant<?>> properties = new LinkedHashMap<>();

        FakeProperties(String path, String interfaceName) {
            this.path = path;
            this.interfaceName = interfaceName;
        }

        public synchronized void property(String name, Object value) {
            properties.put(name, new Variant<>(value));
        }

        public synchronized void omit(String name) { properties.remove(name); }

        boolean flag(String name) { return Boolean.TRUE.equals(GetAll(interfaceName).get(name).getValue()); }

        @Override public String getObjectPath() { return path; }

        @Override
        @SuppressWarnings("unchecked") // D-Bus Properties.Get returns a variant through its generic interface.
        public synchronized <A> A Get(String requestedInterface, String name) {
            requireInterface(requestedInterface);
            return (A) properties.get(name);
        }

        @Override
        public synchronized <A> void Set(String requestedInterface, String name, A value) {
            requireInterface(requestedInterface);
            properties.put(name, value instanceof Variant<?> variant ? variant : new Variant<>(value));
        }

        @Override
        public synchronized Map<String, Variant<?>> GetAll(String requestedInterface) {
            requireInterface(requestedInterface);
            return new LinkedHashMap<>(properties);
        }

        private void requireInterface(String requestedInterface) {
            if (!interfaceName.equals(requestedInterface)) throw new IllegalArgumentException("Unknown interface " + requestedInterface);
        }
    }

    public final class FakeAdapter extends FakeProperties implements Adapter1 {
        private volatile boolean discovering;
        private volatile boolean failStop;
        private volatile int stops;

        FakeAdapter(String path) { super(path, "org.bluez.Adapter1"); }

        public void alreadyScanning() { discovering = true; }
        public void failStop() { failStop = true; }
        public boolean discovering() { return discovering; }
        public int stops() { return stops; }

        @Override
        public void StartDiscovery() {
            if (discovering) throw new org.bluez.Error.InProgress("Another client is scanning");
            discovering = true;
        }

        @Override
        public void StopDiscovery() {
            stops++;
            if (failStop) throw new org.bluez.Error.Failed("Scan already stopped");
            discovering = false;
        }

        @Override
        public void RemoveDevice(DBusPath path) {
            synchronized (FakeBluezService.this) {
                FakeProperties device = objects.get(path);
                if (device == null || !path.getPath().startsWith(getObjectPath() + "/dev_")) {
                    throw new IllegalArgumentException("Device does not belong to this adapter");
                }
                objects.remove(path);
                connection.unExportObject(path.getPath());
            }
        }

        @Override public void SetDiscoveryFilter(Map<String, Variant<?>> filter) { throw new UnsupportedOperationException(); }
        @Override public String[] GetDiscoveryFilters() { throw new UnsupportedOperationException(); }
        @Override public DBusPath ConnectDevice(Map<String, Variant<?>> properties) { throw new UnsupportedOperationException(); }
    }

    public static final class FakeDevice extends FakeProperties implements Device1 {
        private volatile boolean rejectPairing;
        private volatile boolean refuseConnection;
        private final CountDownLatch pairingReply = new CountDownLatch(1);
        private volatile boolean holdPairing;

        FakeDevice(String path) { super(path, "org.bluez.Device1"); }
        public void rejectPairing() { rejectPairing = true; }
        public void refuseConnection() { refuseConnection = true; }
        public void holdPairing() { holdPairing = true; }
        public void releasePairing() { pairingReply.countDown(); }

        @Override
        public void Pair() {
            if (holdPairing) {
                try { pairingReply.await(); }
                catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new org.bluez.Error.Failed("Pairing was interrupted");
                }
            }
            if (rejectPairing) throw new org.bluez.Error.AuthenticationRejected("The speaker refused the PIN");
            if (flag("Paired")) throw new org.bluez.Error.AlreadyExists("The speaker already has a bond");
            property("Paired", true);
        }

        @Override
        public void Connect() {
            if (refuseConnection) throw new org.bluez.Error.ConnectionAttemptFailed("The link could not be established");
            if (flag("Connected")) throw new org.bluez.Error.AlreadyConnected("Link already active");
            property("Connected", true);
        }

        @Override
        public void Disconnect() {
            if (!flag("Connected")) throw new org.bluez.Error.NotConnected("Link is down");
            property("Connected", false);
        }

        @Override public void ConnectProfile(String uuid) { throw new UnsupportedOperationException(); }
        @Override public void DisconnectProfile(String uuid) { throw new UnsupportedOperationException(); }
        @Override public void CancelPairing() { throw new UnsupportedOperationException(); }
    }
}
