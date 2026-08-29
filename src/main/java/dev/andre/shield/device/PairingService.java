package dev.andre.shield.device;

import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.PairingResult;
import dev.andre.shield.protocol.PairingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * Holds the one in-flight pairing attempt.
 *
 * <p>A failed attempt always ends the session: the device shows a brand new code next
 * time, so there is nothing to retry into (spec §5.2).
 */
@Service
public class PairingService {

    /** The pairing port. The command channel is 6466. */
    public static final int PAIRING_PORT = 6467;
    public static final int REMOTE_PORT = 6466;

    private static final Logger log = LoggerFactory.getLogger(PairingService.class);

    private final CertificateStore certificates;
    private final DeviceSessionManager sessions;

    /**
     * One in-flight attempt's state, captured atomically. {@code submit()} reads this
     * field exactly once into a local, so a concurrent {@code begin()} — which replaces
     * the field with a brand new {@code Attempt} rather than mutating fields in place —
     * can never leave {@code submit()} working off a mix of the old attempt's session and
     * the new attempt's credential/host/name.
     */
    private volatile Attempt attempt;

    public PairingService(CertificateStore certificates, DeviceSessionManager sessions) {
        this.certificates = certificates;
        this.sessions = sessions;
    }

    public void begin(String host, String name) throws IOException {
        begin(host, PAIRING_PORT, name);
    }

    /** Port is a parameter only so tests can point at an in-process fake device. */
    public void begin(String host, int port, String name) throws IOException {
        cancel();
        String resolvedName = (name == null || name.isBlank()) ? host : name;
        String deviceId = deviceId(host);
        ClientCertificate credential = certificates.loadOrCreate(deviceId);

        PairingSession starting = new PairingSession(host, port, credential);
        try {
            starting.start();
        } catch (IOException | RuntimeException e) {
            // Nothing has taken ownership of the session yet, so nothing else will ever
            // close it — and this is the most-exercised error path in the app.
            starting.close();
            throw e;
        }
        this.attempt = new Attempt(starting, credential, host, resolvedName, deviceId);
    }

    public boolean inProgress() {
        return attempt != null;
    }

    public PairingResult submit(String code) {
        Attempt current = attempt;
        if (current == null) {
            return new PairingResult.Failed("No pairing is in progress; start again from the device list");
        }

        try {
            PairingResult result = current.session().submitCode(code);
            if (result instanceof PairingResult.Paired paired) {
                certificates.save(current.deviceId(), current.credential());
                sessions.adopt(new Device(
                        current.deviceId(),
                        current.name(),
                        current.host(),
                        REMOTE_PORT,
                        ClientCertificate.fingerprintOf(paired.serverCertificate()),
                        Instant.now()));
                log.info("Paired with {} at {}", current.name(), current.host());
            }
            return result;
        } finally {
            // The device shows a brand new code next time whatever happened here, so the
            // attempt is over either way. In a finally because an exception out of adopt()
            // would otherwise strand inProgress() at true forever, leaving the setup page
            // showing a code form for a session that is already dead.
            cancel();
        }
    }

    public void cancel() {
        Attempt current = attempt;
        attempt = null;
        if (current != null) {
            current.session().close();
        }
    }

    /**
     * Stable across re-pairings so the same certificate alias and registry entry are
     * reused. Derived from the host alone — NOT the display name, which the user can
     * change freely — so re-pairing the same physical device under a new name replaces
     * its existing registry entry instead of creating a duplicate (spec §6).
     */
    private static String deviceId(String host) {
        return host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private record Attempt(PairingSession session, ClientCertificate credential,
                           String host, String name, String deviceId) {
    }
}
