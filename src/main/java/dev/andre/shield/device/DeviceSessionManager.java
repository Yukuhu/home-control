package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
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
        certificates.verifyReadable();
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
        Optional<Device> device = registry.first();
        if (device.isEmpty()) {
            return DeviceState.initial();
        }
        DeviceSession session = sessions.get(device.get().id());
        return session == null ? DeviceState.unpaired() : session.state();
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
        Optional<Device> registered = registry.findById(id);
        if (registered.isEmpty()) {
            return;
        }
        Device device = registered.get();
        DeviceSession session = sessions.remove(device.id());
        if (session != null) {
            session.close();
        }
        registry.delete(device.id());
        certificates.delete(device.certificateAlias());
        events.publishEvent(new DeviceStateChangedEvent(state()));
    }

    private void startSession(Device device) {
        DeviceSession existing = sessions.remove(device.id());
        if (existing != null) {
            existing.close();
        }

        Optional<ClientCertificate> credential = certificates.load(device.certificateAlias());
        if (credential.isEmpty()) {
            events.publishEvent(new DeviceStateChangedEvent(DeviceState.unpaired()));
            return;
        }

        DeviceSession session = new DeviceSession(device,
                credential.get(),
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
