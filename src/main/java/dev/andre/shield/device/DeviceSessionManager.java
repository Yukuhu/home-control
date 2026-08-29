package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.CertificateStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link DeviceSession} per registered device. The v1 UI drives whichever
 * device is first in the registry; the map is what makes "multiple devices later"
 * a UI change rather than a rewrite.
 */
@Service
public class DeviceSessionManager implements AutoCloseable {

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final CertificateStore certificates;
    private final ShieldProperties properties;
    private final ApplicationEventPublisher events;

    public DeviceSessionManager(DeviceRegistry registry, CertificateStore certificates,
                                ShieldProperties properties, ApplicationEventPublisher events) {
        this.registry = registry;
        this.certificates = certificates;
        this.properties = properties;
        this.events = events;
    }

    @PostConstruct
    public void startRegisteredDevices() {
        // Only the active device, not every entry. A re-pair at a changed address leaves a
        // stale entry behind (the id is derived from the host, spec §6), and since
        // DeviceStateChangedEvent carries no device id, a second session's DISCONNECTED
        // events would reach every tab and overwrite the live device's badge. The v1 UI
        // drives first() and nothing else.
        registry.first().ifPresent(this::startSession);
    }

    public Optional<DeviceSession> active() {
        return registry.first().map(device -> sessions.get(device.id()));
    }

    public DeviceState state() {
        return active().map(DeviceSession::state).orElseGet(DeviceState::initial);
    }

    public Optional<Device> activeDevice() {
        return registry.first();
    }

    /** Registers a freshly paired device and brings its session up. */
    public void adopt(Device device) {
        registry.save(device);
        startSession(device);
    }

    public void forget(String id) {
        DeviceSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
        registry.delete(id);
    }

    private void startSession(Device device) {
        DeviceSession existing = sessions.remove(device.id());
        if (existing != null) {
            existing.close();
        }
        DeviceSession session = new DeviceSession(device,
                certificates.loadOrCreate(device.certificateAlias()),
                properties,
                state -> events.publishEvent(new DeviceStateChangedEvent(state)));
        sessions.put(device.id(), session);
        session.start();
    }

    @Override
    @PreDestroy
    public void close() {
        sessions.values().forEach(DeviceSession::close);
        sessions.clear();
    }
}
