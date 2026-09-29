package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.adapters.androidtv.protocol.PairingResult;
import dev.andre.homecontrol.adapters.androidtv.protocol.PairingSession;
import dev.andre.homecontrol.core.CodePairing;
import dev.andre.homecontrol.core.CodePairingOutcome;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.storage.DataDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the one in-flight pairing attempt.
 *
 * <p>A failed attempt always ends the session: the device shows a brand new code next
 * time, so there is nothing to retry into (spec §5.2).
 */
public class PairingService implements CodePairing {

    /** The pairing port. The command channel is {@link AndroidTvSettings#DEFAULT_PORT}. */
    public static final int PAIRING_PORT = 6467;

    private static final Logger log = LoggerFactory.getLogger(PairingService.class);

    private final CertificateStore certificates;
    private final DeviceEnrollment enrollment;
    private final DataDirectory dataDirectory;

    /**
     * One in-flight attempt's state, captured atomically. {@code submit()} reads this
     * reference exactly once into a local, so a concurrent {@code begin()} — which replaces
     * it with a brand new {@code Attempt} rather than mutating fields in place — can never
     * leave {@code submit()} working off a mix of the old attempt's session and the new
     * attempt's credential/host/name. Whoever takes an attempt out of here closes its session,
     * so every session is closed exactly once, and an attempt that ends only ever clears itself.
     */
    private final AtomicReference<Attempt> attempt = new AtomicReference<>();

    public PairingService(CertificateStore certificates, DeviceEnrollment enrollment,
                          DataDirectory dataDirectory) {
        this.certificates = certificates;
        this.enrollment = enrollment;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public void begin(String host, String name) throws IOException {
        begin(host, PAIRING_PORT, name);
    }

    /** Port is a parameter only so tests can point at an in-process fake device. */
    public void begin(String host, int port, String name) throws IOException {
        cancel();
        dataDirectory.verifyWritable();
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
        // A concurrent begin() may have installed its attempt meanwhile: the newest one wins.
        close(attempt.getAndSet(new Attempt(starting, credential, host, resolvedName, deviceId)));
    }

    @Override
    public boolean inProgress() {
        return attempt.get() != null;
    }

    /**
     * Reports in {@link CodePairingOutcome}, not the protocol-level {@link PairingResult}: the
     * web layer must not import {@code adapters.androidtv.protocol} (global constraint), so
     * this is where the internal handshake result is translated into the public outcome.
     */
    @Override
    public CodePairingOutcome submit(String code) {
        Attempt current = attempt.get();
        if (current == null) {
            return new CodePairingOutcome.Failed("No pairing is in progress; start again from the device list");
        }

        try {
            PairingResult result = current.session().submitCode(code);
            return switch (result) {
                case PairingResult.Paired(var serverCertificate) -> {
                    certificates.save(current.deviceId(), current.credential());
                    enrollment.adopt(AndroidTvSettings.device(
                            current.deviceId(),
                            current.name(),
                            current.host(),
                            AndroidTvSettings.DEFAULT_PORT,
                            ClientCertificate.fingerprintOf(serverCertificate),
                            Instant.now()));
                    log.info("Paired with {} at {}", current.name(), current.host());
                    yield new CodePairingOutcome.Paired();
                }
                case PairingResult.WrongCode _ -> new CodePairingOutcome.WrongCode();
                case PairingResult.Failed(var reason) -> new CodePairingOutcome.Failed(reason);
            };
        } finally {
            // The device shows a brand new code next time whatever happened here, so the
            // attempt is over either way. In a finally because an exception out of adopt()
            // would otherwise strand inProgress() at true forever, leaving the setup page
            // showing a code form for a session that is already dead. Only this attempt ends: a
            // begin() that replaced it meanwhile has already closed it and must keep its own.
            if (attempt.compareAndSet(current, null)) {
                close(current);
            }
        }
    }

    public void cancel() {
        close(attempt.getAndSet(null));
    }

    private static void close(Attempt ended) {
        if (ended != null) {
            ended.session().close();
        }
    }

    /**
     * Stable across re-pairings so the same certificate alias and registry entry are
     * reused. Derived from the host alone — NOT the display name, which the user can
     * change freely — so re-pairing the same physical device under a new name replaces
     * its existing registry entry instead of creating a duplicate (spec §6).
     */
    private static String deviceId(String host) {
        return host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-)|(-$)", "");
    }

    private record Attempt(PairingSession session, ClientCertificate credential,
                           String host, String name, String deviceId) {
    }
}
