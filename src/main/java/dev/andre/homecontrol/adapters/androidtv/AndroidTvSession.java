package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.adapters.androidtv.protocol.DisconnectCause;
import dev.andre.homecontrol.adapters.androidtv.protocol.RemoteConnection;
import dev.andre.homecontrol.adapters.androidtv.protocol.RemoteListener;
import dev.andre.homecontrol.adapters.androidtv.protocol.TlsSockets;
import dev.andre.homecontrol.adapters.support.Backoff;
import dev.andre.homecontrol.adapters.support.ConnectionSlot;
import dev.andre.homecontrol.adapters.support.DeviceCalls;
import dev.andre.homecontrol.adapters.support.Reconnector;
import dev.andre.homecontrol.adapters.support.SessionLoop;
import dev.andre.homecontrol.adapters.support.StatePublisher;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LaunchedMedia;
import dev.andre.homecontrol.core.RedactedUris;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One device's live connection: connects, keeps the last known {@link DeviceState}, and reconnects with a growing
 * backoff, except when the device has rejected the pairing, where retrying is pointless (spec §8). TLS alone does not
 * make the session usable: the {@link Reconnector}'s attempt stays pending until the device finishes the Remote v2
 * configure/active exchange. Everything that changes the session runs on its loop, and a callback of a connection that
 * is no longer the current one is dropped there.
 */
public class AndroidTvSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(AndroidTvSession.class);

    /**
     * A single UNPAIRED verdict is ambiguous: it fires both for a genuinely de-paired
     * device and for a connection that drops before the app-level handshake finishes
     * (indistinguishable from here — see {@code RemoteConnection.classify}). Requiring
     * this many in a row before latching keeps an ordinary reboot from being mistaken
     * for a de-pairing: on the default 1s-doubling ramp the fifth verdict lands about
     * half a minute after the first, past the window in which a rebooting device is
     * accepting TLS connections but tearing them down again. Latching earlier is the
     * damaging mistake, because UNPAIRED never schedules another attempt — while a real
     * re-pair only has to be noticed eventually, and a fingerprint MISMATCH, which is
     * not ambiguous at all, still latches on the first occurrence.
     */
    private static final int UNPAIRED_CONFIRMATION_THRESHOLD = 5;

    private final Device device;
    private final ConnectionOpener opener;
    private final StatePublisher publisher;
    private final SessionLoop loop;
    /** When what was launched next stops counting as playing; its own slot, so a reconnect never cancels it. */
    private final SessionLoop.Timer playbackExpiry;
    private final Reconnector reconnector;
    private final ConnectionSlot<RemoteConnection> connection;
    private final Runnable onClosed;

    /** Loop thread only. */
    private int consecutiveUnpaired;
    /** Loop thread only. Outlives a reconnect: the device keeps playing. */
    private final InferredPlayback playback;

    public AndroidTvSession(Device device, ClientCertificate credential,
                         AndroidTvProperties properties, Consumer<DeviceState> onChange, Runnable onClosed) {
        this(device, credential, AndroidTvTimings.from(properties), onChange, null, onClosed);
    }

    @FunctionalInterface
    interface ConnectionOpener {
        RemoteConnection open(RemoteListener listener) throws IOException;
    }

    AndroidTvSession(Device device, ClientCertificate credential,
                     AndroidTvTimings timings, Consumer<DeviceState> onChange,
                     ConnectionOpener opener) {
        this(device, credential, timings, onChange, opener, () -> { });
    }

    AndroidTvSession(Device device, ClientCertificate credential,
                     AndroidTvTimings timings, Consumer<DeviceState> onChange,
                     ConnectionOpener opener, Runnable onClosed) {
        this.device = device;
        AndroidTvSettings settings = AndroidTvSettings.of(device);
        this.opener = opener == null
                ? listener -> RemoteConnection.connect(device.host(), settings.port(), credential,
                        Math.toIntExact(timings.staleTimeout().toMillis()), listener, settings.certificateFingerprint())
                : opener;
        this.publisher = new StatePublisher(device.id(), DeviceState.initial(), onChange);
        this.loop = new SessionLoop("shield-session-" + device.id());
        this.playbackExpiry = loop.timer();
        this.playback = new InferredPlayback(timings.playbackAppGrace(), timings.playbackExpiryMargin());
        this.reconnector = new Reconnector(loop, new Backoff(timings.reconnectInitialDelay(),
                timings.reconnectMaxDelay()), this::connect);
        this.connection = new ConnectionSlot<>("shield-session-" + device.id());
        this.onClosed = onClosed;
    }

    public void start() {
        reconnector.start();
    }

    public String host() {
        return device.host();
    }

    /**
     * The device announced itself, so it may be back: try now instead of waiting out the backoff. Not while ambiguous
     * UNPAIRED verdicts count towards the latch: a booting device announces itself before it takes the certificate,
     * and each early attempt would count one more.
     */
    public void reconnectNow() {
        loop.execute(() -> {
            if (consecutiveUnpaired == 0) {
                reconnector.reconnectNow();
            }
        });
    }

    public DeviceState state() {
        return publisher.current();
    }

    public void sendKey(RemoteKey key) {
        sendKey(key, KeyPress.SHORT);
    }

    public void sendKey(RemoteKey key, KeyPress press) {
        RemoteConnection current = requireConnected();
        DeviceCalls.run(device.name(), "press " + key.label(),
                () -> current.sendKey(AndroidTvKeys.code(key), AndroidTvKeys.direction(press)));
    }

    public void openAppLink(URI uri) {
        RemoteConnection current = requireConnected();
        DeviceCalls.run(device.name(), "open " + RedactedUris.withoutQuery(uri), () -> current.sendAppLink(uri.toString()));
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey(var key, var press) -> sendKey(key, press);
            case Action.OpenAppLink(var uri, var media) -> {
                openAppLink(uri);
                if (media != null) {
                    loop.execute(() -> handleLaunched(media));
                }
            }
            case Action.SetVolume _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 has no absolute volume; use the volume keys");
            case Action.Mute _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot set mute directly; use the mute key");
            case Action.Stop _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot stop a cast");
            case Action.CastLoad _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot load Cast media");
            case Action.CastMessage _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot run Cast receiver apps");
            case Action.SelectInput _ -> throw new UnsupportedActionException(
                    "Android TV does not list its inputs; switch inputs from the Home screen");
            case Action.PlayMedia _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot play a direct stream");
            case Action.Pause _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot pause a direct stream; use the play/pause key");
            case Action.Resume _ -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot resume a direct stream; use the play/pause key");
            case Action.JoinGroup _ -> throw new UnsupportedActionException("Android TV cannot be grouped");
            case Action.LeaveGroup _ -> throw new UnsupportedActionException("Android TV cannot be grouped");
        }
    }

    private RemoteConnection requireConnected() {
        Optional<RemoteConnection> current = connection.current();
        DeviceStatus status = publisher.current().status();
        if (current.isEmpty() || status != DeviceStatus.CONNECTED) {
            if (status == DeviceStatus.UNPAIRED) {
                throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
            }
            throw DeviceCalls.notConnected(device.name());
        }
        return current.get();
    }

    /** Runs on the loop, through the {@link Reconnector}. */
    private Reconnector.Outcome connect() {
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTING));
        Attempt attempt = new Attempt();
        try {
            RemoteConnection opened = opener.open(attempt);
            attempt.own = opened;
            if (!connection.set(opened)) {
                return Reconnector.Outcome.STOP; // closed during the handshake; the slot closed the connection
            }
            // TLS only proves transport setup. The reader reports Remote v2 readiness after the device's
            // configure/active exchange, on this loop.
            return Reconnector.Outcome.PENDING;
        } catch (TlsSockets.CertificateMismatchException _) {
            log.warn("Device {} presented an unexpected certificate; refusing it", device.id());
            publisher.update(state -> state.withStatus(DeviceStatus.UNPAIRED));
            return Reconnector.Outcome.STOP;
        } catch (RemoteConnection.UnpairedException _) {
            return ambiguousUnpaired() ? Reconnector.Outcome.RETRY : Reconnector.Outcome.STOP;
        } catch (IOException e) {
            log.debug("Could not reach {}: {}", device.host(), e.getMessage());
            forgetAmbiguousVerdicts();
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            return Reconnector.Outcome.RETRY;
        }
    }

    private void handleReady() {
        if (publisher.current().status() != DeviceStatus.CONNECTING) {
            return;
        }
        forgetAmbiguousVerdicts();
        reconnector.connected();
        publisher.update(state -> state.withStatus(DeviceStatus.CONNECTED));
    }

    /**
     * Counts an ambiguous UNPAIRED verdict — from either {@link RemoteConnection.UnpairedException}
     * or {@link DisconnectCause#UNPAIRED} — and says whether to retry like an ordinary drop: until it
     * has happened {@value #UNPAIRED_CONFIRMATION_THRESHOLD} times in a row, spanning a plausible
     * device reboot. Then the session latches UNPAIRED.
     * A certificate fingerprint MISMATCH is not ambiguous and does not go through here —
     * it latches immediately, on the first occurrence (see {@link TlsSockets.CertificateMismatchException}).
     */
    private boolean ambiguousUnpaired() {
        consecutiveUnpaired++;
        if (consecutiveUnpaired < UNPAIRED_CONFIRMATION_THRESHOLD) {
            log.info("Device {} looked unpaired ({}/{}); retrying before giving up",
                    device.id(), consecutiveUnpaired, UNPAIRED_CONFIRMATION_THRESHOLD);
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            return true;
        }
        log.warn("Could not establish the remote session for {} after {} authentication-like failures; try pairing again",
                device.id(), consecutiveUnpaired);
        publisher.update(state -> state.withStatus(DeviceStatus.UNPAIRED));
        return false;
    }

    /**
     * Clears the ambiguous-verdict count after any outcome that was NOT ambiguous — a
     * successful connection, or a network-class failure (spec §8 class 1), or a drop the
     * device explained some other way. The latch is a rule about CONSECUTIVE verdicts: a
     * device that could not be reached at all is positive evidence that the verdicts before
     * it were not a rejected certificate, so counting them together would let a merely flaky
     * device accumulate a latch over days and tell the user to re-pair when nothing is wrong.
     * A fingerprint MISMATCH does not come through here at all; it still latches at once.
     */
    private void forgetAmbiguousVerdicts() {
        consecutiveUnpaired = 0;
    }

    private void handleLaunched(LaunchedMedia media) {
        playback.launched(media, publisher.current().currentApp(), Instant.now());
        refreshPlayback();
    }

    /** Publishes what plays now, then comes back when that is next due to change by itself. */
    private void refreshPlayback() {
        Instant now = Instant.now();
        publisher.update(state -> state.withNowPlaying(playback.current(now)));
        playback.nextDeadline().ifPresentOrElse(
                deadline -> playbackExpiry.schedule(this::refreshPlayback,
                        Duration.ofMillis(Math.max(0, Duration.between(now, deadline).toMillis()) + 1)),
                playbackExpiry::cancel);
    }

    @Override
    public void close() {
        publisher.close();
        loop.close();
        connection.close();
        onClosed.run();
    }

    /**
     * One attempt's listener. Its callbacks arrive on the protocol reader or idle-watchdog thread, which
     * {@link RemoteConnection} starts before its factory returns; each hands its work to the loop, where
     * {@code connect()} itself runs, so every change of state happens on one thread and in order.
     */
    private final class Attempt implements RemoteListener {

        /** Set on the loop before any callback of this attempt runs there. */
        private RemoteConnection own;

        private void onLoop(Runnable task) {
            loop.execute(() -> {
                if (own != null && connection.current().filter(live -> live == own).isPresent()) {
                    task.run();
                }
            });
        }

        private void handlePower(boolean on) {
            if (!on) {
                playback.poweredOff();
            }
            publisher.update(state -> state.withPower(on).withNowPlaying(playback.current(Instant.now())));
        }

        private void handleCurrentApp(String appPackage) {
            playback.appChanged(appPackage);
            publisher.update(state -> state.withCurrentApp(appPackage).withNowPlaying(playback.current(Instant.now())));
        }

        private void handleDisconnect(DisconnectCause cause) {
            if (own == null || !connection.takeIf(own)) {
                return; // a connection that is no longer the current one
            }
            if (cause == DisconnectCause.UNPAIRED) {
                if (ambiguousUnpaired()) {
                    reconnector.lost();
                } else {
                    reconnector.stop();
                }
                return;
            }
            log.info("Lost the connection to {} ({}); reconnecting", device.id(), cause);
            forgetAmbiguousVerdicts();
            publisher.update(state -> state.withStatus(DeviceStatus.DISCONNECTED));
            reconnector.lost();
        }

        @Override
        public void onReady() {
            onLoop(AndroidTvSession.this::handleReady);
        }

        @Override
        public void onPower(boolean on) {
            onLoop(() -> handlePower(on));
        }

        @Override
        public void onCurrentApp(String appPackage) {
            onLoop(() -> handleCurrentApp(appPackage));
        }

        @Override
        public void onVolume(int level, int max, boolean muted) {
            onLoop(() -> publisher.update(state -> state.withVolume(level, max, muted)));
        }

        @Override
        public void onDisconnected(DisconnectCause cause) {
            loop.execute(() -> handleDisconnect(cause));
        }
    }
}
