package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An in-memory BlueZ for tests: no D-Bus, no host. Fully synchronized: sessions poll it from their own thread. */
public final class FakeBluezClient implements BluezClient {

    public static final String ADAPTER = "00:1A:7D:DA:71:13";

    private record AdapterRecord(String id, String address, String alias) {
    }

    private final List<AdapterRecord> adapters = new ArrayList<>();
    private final Map<String, Boolean> powered = new LinkedHashMap<>();
    private final Map<String, FakeDevice> devices = new LinkedHashMap<>();

    private final List<String> calls = new ArrayList<>();
    private int reads;

    private volatile BluezFailure unavailable;
    private final Map<String, BluezFailure> failNext = new LinkedHashMap<>();
    private final Map<String, String> failNextDetail = new LinkedHashMap<>();
    private final Map<String, BluezFailure> failAlways = new LinkedHashMap<>();
    private final Map<String, String> failAlwaysDetail = new LinkedHashMap<>();
    private final Map<String, Duration> delays = new LinkedHashMap<>();

    private volatile boolean closed;

    public FakeBluezClient() {
        addAdapter("hci0", ADAPTER, true);
    }

    public synchronized FakeBluezClient noAdapters() {
        adapters.clear();
        return this;
    }

    public synchronized FakeBluezClient adapterPowered(boolean value) {
        powered.put(ADAPTER, value);
        return this;
    }

    public synchronized FakeBluezClient addAdapter(String id, String address, boolean isPowered) {
        adapters.add(new AdapterRecord(id, address, id));
        powered.put(address, isPowered);
        return this;
    }

    public synchronized FakeDevice addDevice(String address, String name) {
        FakeDevice device = new FakeDevice(address, name, false);
        devices.put(key(address), device);
        return device;
    }

    public synchronized FakeDevice known(String address, String name) {
        FakeDevice device = addDevice(address, name);
        device.visible = true;
        return device;
    }

    public synchronized FakeDevice device(String address) {
        return devices.get(key(address));
    }

    public synchronized void unavailable(BluezFailure failure) {
        this.unavailable = failure;
    }

    public synchronized void failNext(String operation, BluezFailure failure, String detail) {
        failNext.put(operation, failure);
        failNextDetail.put(operation, detail);
    }

    public synchronized void failAlways(String operation, BluezFailure failure, String detail) {
        failAlways.put(operation, failure);
        failAlwaysDetail.put(operation, detail);
    }

    public synchronized void heal(String operation) {
        failAlways.remove(operation);
        failAlwaysDetail.remove(operation);
    }

    public synchronized void delay(String operation, Duration delay) {
        delays.put(operation, delay);
    }

    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    public synchronized void clearCalls() {
        calls.clear();
    }

    public synchronized int reads() {
        return reads;
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public synchronized List<BluetoothAdapterInfo> adapters() throws BluezException {
        checkUnavailable();
        maybeFail("adapters");
        reads++;
        List<BluetoothAdapterInfo> result = new ArrayList<>();
        for (AdapterRecord record : adapters) {
            result.add(new BluetoothAdapterInfo(record.id(), record.address(), record.alias(),
                    powered.getOrDefault(record.address(), false)));
        }
        return result;
    }

    @Override
    public synchronized void powerOn(String adapterAddress) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        calls.add("powerOn " + adapterAddress);
        maybeFail("powerOn");
        powered.put(adapterAddress, true);
    }

    @Override
    public synchronized List<BluetoothDeviceInfo> discover(String adapterAddress, Duration duration) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        calls.add("discover " + adapterAddress + " " + duration.toSeconds() + "s");
        maybeFail("discover");
        devices.values().forEach(device -> device.visible = true);
        return devicesOf(adapterAddress);
    }

    @Override
    public synchronized List<BluetoothDeviceInfo> devices(String adapterAddress) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        maybeFail("devices");
        reads++;
        return devicesOf(adapterAddress);
    }

    @Override
    public synchronized Optional<BluetoothDeviceInfo> device(String adapterAddress, String address) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        maybeFail("device");
        reads++;
        FakeDevice found = devices.get(key(address));
        return found != null && found.visible ? Optional.of(found.info()) : Optional.empty();
    }

    @Override
    public synchronized void pair(String adapterAddress, String address) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        FakeDevice device = requireVisibleDevice(address);
        calls.add("pair " + address);
        maybeFail("pair");
        device.paired = true;
        if (device.uuidsAfterPairing != null) {
            device.uuids = device.uuidsAfterPairing;
        }
    }

    @Override
    public synchronized void trust(String adapterAddress, String address) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        FakeDevice device = requireVisibleDevice(address);
        calls.add("trust " + address);
        maybeFail("trust");
        device.trusted = true;
    }

    @Override
    public synchronized void connect(String adapterAddress, String address) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        FakeDevice device = requireVisibleDevice(address);
        calls.add("connect " + address);
        maybeFail("connect");
        device.connected = true;
    }

    @Override
    public synchronized void disconnect(String adapterAddress, String address) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        FakeDevice device = requireVisibleDevice(address);
        calls.add("disconnect " + address);
        maybeFail("disconnect");
        device.connected = false;
    }

    @Override
    public synchronized void remove(String adapterAddress, String address) throws BluezException {
        checkUnavailable();
        requireAdapter(adapterAddress);
        calls.add("remove " + address);
        maybeFail("remove");
        devices.remove(key(address));
    }

    @Override
    public void close() {
        closed = true;
    }

    private List<BluetoothDeviceInfo> devicesOf(String adapterAddress) {
        // The fake has one implicit adapter for every device; a configured second adapter simply sees none.
        if (!ADAPTER.equalsIgnoreCase(adapterAddress)) {
            return List.of();
        }
        List<BluetoothDeviceInfo> result = new ArrayList<>();
        devices.values().stream().filter(device -> device.visible).forEach(device -> result.add(device.info()));
        return result;
    }

    private void requireAdapter(String adapterAddress) throws BluezException {
        boolean known = adapters.stream().anyMatch(record -> record.address().equalsIgnoreCase(adapterAddress));
        if (!known) {
            throw new BluezException(BluezFailure.NO_ADAPTER, BluezFailures.message(BluezFailure.NO_ADAPTER, adapterAddress));
        }
    }

    private FakeDevice requireVisibleDevice(String address) throws BluezException {
        FakeDevice device = devices.get(key(address));
        if (device == null || !device.visible) {
            throw new BluezException(BluezFailure.NOT_FOUND, BluezFailures.message(BluezFailure.NOT_FOUND, address));
        }
        return device;
    }

    private void checkUnavailable() throws BluezException {
        BluezFailure failure = unavailable;
        if (failure != null) {
            throw new BluezException(failure, BluezFailures.message(failure, "fake"));
        }
    }

    private void maybeFail(String operation) throws BluezException {
        Duration delay = delays.get(operation);
        if (delay != null) {
            sleep(delay);
        }
        BluezFailure always = failAlways.get(operation);
        if (always != null) {
            throw new BluezException(always, BluezFailures.message(always, failAlwaysDetail.get(operation)));
        }
        BluezFailure next = failNext.remove(operation);
        if (next != null) {
            String detail = failNextDetail.remove(operation);
            throw new BluezException(next, BluezFailures.message(next, detail));
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static String key(String address) {
        return address.toUpperCase(java.util.Locale.ROOT);
    }

    public static final class FakeDevice {
        private final String address;
        private String name;
        private String icon;
        private volatile boolean paired;
        private volatile boolean trusted;
        private volatile boolean connected;
        private volatile List<String> uuids = List.of();
        private volatile List<String> uuidsAfterPairing;
        private Short rssi;
        private volatile boolean visible;

        private FakeDevice(String address, String name, boolean visible) {
            this.address = address;
            this.name = name;
            this.visible = visible;
        }

        public FakeDevice name(String value) {
            this.name = value;
            return this;
        }

        public FakeDevice icon(String value) {
            this.icon = value;
            return this;
        }

        public FakeDevice paired(boolean value) {
            this.paired = value;
            return this;
        }

        public FakeDevice trusted(boolean value) {
            this.trusted = value;
            return this;
        }

        public FakeDevice connected(boolean value) {
            this.connected = value;
            return this;
        }

        public FakeDevice uuids(String... values) {
            this.uuids = List.of(values);
            return this;
        }

        public FakeDevice uuidsAfterPairing(String... values) {
            this.uuidsAfterPairing = List.of(values);
            return this;
        }

        public FakeDevice rssi(int value) {
            this.rssi = (short) value;
            return this;
        }

        public boolean paired() {
            return paired;
        }

        public boolean trusted() {
            return trusted;
        }

        public boolean connected() {
            return connected;
        }

        public BluetoothDeviceInfo info() {
            return new BluetoothDeviceInfo(address, name, icon, paired, trusted, connected, uuids, rssi);
        }
    }
}
