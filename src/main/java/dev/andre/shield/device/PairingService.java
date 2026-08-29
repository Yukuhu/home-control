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

    private volatile PairingSession session;
    private volatile ClientCertificate credential;
    private volatile String host;
    private volatile String name;

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
        this.host = host;
        this.name = (name == null || name.isBlank()) ? host : name;
        this.credential = certificates.loadOrCreate(deviceId());

        PairingSession starting = new PairingSession(host, port, credential);
        starting.start();
        this.session = starting;
    }

    public boolean inProgress() {
        return session != null;
    }

    public PairingResult submit(String code) {
        PairingSession current = session;
        if (current == null) {
            return new PairingResult.Failed("No pairing is in progress; start again from the device list");
        }

        PairingResult result = current.submitCode(code);
        if (result instanceof PairingResult.Paired paired) {
            certificates.save(deviceId(), credential);
            sessions.adopt(new Device(
                    deviceId(),
                    name,
                    host,
                    REMOTE_PORT,
                    ClientCertificate.fingerprintOf(paired.serverCertificate()),
                    Instant.now()));
            log.info("Paired with {} at {}", name, host);
        }
        cancel();
        return result;
    }

    public void cancel() {
        PairingSession current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    /** Stable across re-pairings so the same certificate alias and registry entry are reused. */
    private String deviceId() {
        String base = (name == null || name.isBlank()) ? host : name;
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
