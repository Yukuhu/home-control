package dev.andre.homecontrol.adapters.androidtv.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteAppLinkLaunchRequest;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteConfigure;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDeviceInfo;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteKeyCode;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteKeyInject;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteMessage;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemotePingResponse;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteSetActive;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteSetVolumeLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * The long-lived command channel on port 6466.
 *
 * <p>The device drives the handshake and then pushes state unprompted; a reader thread
 * answers its pings and forwards everything else to the {@link RemoteListener}.
 */
public class RemoteConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteConnection.class);

    private static final int FEATURE_KEY = 1 << 1;      // 2
    private static final int FEATURE_IME_RECEIVE = 1 << 2; // 4, supplies current-app events
    private static final int FEATURE_POWER = 1 << 5;    // 32
    private static final int FEATURE_VOLUME = 1 << 6;   // 64
    private static final int FEATURE_APP_LINK = 1 << 9;  // 512, RemoteAppLinkLaunchRequest
    private static final int CLIENT_FEATURES = FEATURE_KEY
            | FEATURE_IME_RECEIVE
            | FEATURE_POWER
            | FEATURE_VOLUME
            | FEATURE_APP_LINK;

    private final SSLSocket socket;
    private final MessageStream stream;
    private final RemoteListener listener;
    private final X509Certificate serverCertificate;
    private final long staleTimeoutNanos;
    private final Thread idleWatchdog;

    /** Guarded by this connection's monitor, together with the idle-expiry decision. */
    private long lastActivityNanos = System.nanoTime();

    private volatile boolean configured;
    private volatile boolean closed;

    public static RemoteConnection connect(String host, int port, ClientCertificate credential,
                                           int staleTimeoutMillis, RemoteListener listener)
            throws IOException {
        return connect(host, port, credential, staleTimeoutMillis, listener, null);
    }

    public static RemoteConnection connect(String host, int port, ClientCertificate credential,
                                           int staleTimeoutMillis, RemoteListener listener,
                                           String expectedFingerprint)
            throws IOException {
        SSLSocket socket;
        try {
            socket = TlsSockets.connect(host, port, credential, staleTimeoutMillis, expectedFingerprint);
        } catch (TlsSockets.HandshakeRejectedException e) {
            // A handshake failure can indicate a rejected credential, but also a TLS or
            // protocol problem. Keep it distinct from TCP connect failures so the session
            // can confirm repeated ambiguous verdicts before asking the user to re-pair.
            throw new UnpairedException(e.getMessage(), e);
        }
        try {
            return new RemoteConnection(socket, listener);
        } catch (IOException | RuntimeException e) {
            // Reading the streams/certificate or configuring the timeout can fail before
            // the background threads start and before an owner holds the open socket.
            closeQuietly(socket);
            throw e;
        }
    }

    private static void closeQuietly(SSLSocket socket) {
        try {
            socket.close();
        } catch (IOException _) {
            // Already gone.
        }
    }

    private RemoteConnection(SSLSocket socket, RemoteListener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
        this.serverCertificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        this.staleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(socket.getSoTimeout());
        // The device postpones pings while receiving commands. A read-only timeout
        // would disconnect an active remote. Watch traffic in both directions instead.
        // Keep the parser's read uninterrupted: retrying after a timeout could lose a
        // partially read protobuf frame. TLS establishment still uses the socket timeout.
        socket.setSoTimeout(0);
        this.idleWatchdog = Thread.ofVirtual().name("shield-remote-idle").unstarted(this::watchIdle);
        if (staleTimeoutNanos > 0) {
            idleWatchdog.start();
        }
        Thread.ofVirtual().name("shield-remote-reader").start(this::readLoop);
    }

    /** The certificate the device presented; any supplied pin was verified during TLS. */
    public X509Certificate serverCertificate() {
        return serverCertificate;
    }

    public void sendKey(RemoteKey key) throws IOException {
        sendKey(key, KeyPress.SHORT);
    }

    public void sendKey(RemoteKey key, KeyPress press) throws IOException {
        write(RemoteMessage.newBuilder()
                .setRemoteKeyInject(RemoteKeyInject.newBuilder()
                        .setKeyCode(RemoteKeyCode.forNumber(key.code()))
                        .setDirection(direction(press)))
                .build());
    }

    private static RemoteDirection direction(KeyPress press) {
        return switch (press) {
            case SHORT -> RemoteDirection.SHORT;
            case START_LONG -> RemoteDirection.START_LONG;
            case END_LONG -> RemoteDirection.END_LONG;
        };
    }

    /**
     * Asks the device to open {@code uri} with whatever app claims it. There is no reply:
     * success shows up, if at all, as a later current-app event (spec §5.3).
     */
    public void sendAppLink(String uri) throws IOException {
        write(RemoteMessage.newBuilder()
                .setRemoteAppLinkLaunchRequest(RemoteAppLinkLaunchRequest.newBuilder().setAppLink(uri))
                .build());
    }

    private void write(RemoteMessage message) throws IOException {
        stream.write(message);
        recordActivity();
    }

    private synchronized void recordActivity() {
        lastActivityNanos = System.nanoTime();
    }

    private void watchIdle() {
        try {
            while (!closed) {
                long remaining = checkIdle();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(remaining);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized long checkIdle() {
        if (closed) {
            return 0;
        }
        long remaining = staleTimeoutNanos - (System.nanoTime() - lastActivityNanos);
        if (remaining <= 0) {
            finish(DisconnectCause.STALE);
        }
        return remaining;
    }

    private void readLoop() {
        try {
            RemoteMessage message;
            while (!closed && (message = stream.read(RemoteMessage.parser())) != null) {
                recordActivity();
                dispatch(message);
            }
            finish(DisconnectCause.CLOSED);
        } catch (InvalidProtocolBufferException e) {
            // protobuf's delimited-parsing helpers catch ANY IOException raised while reading
            // the length prefix or the message body and rewrap it as InvalidProtocolBufferException
            // (their catch-all for "the stream didn't hold a valid message"), with the real
            // exception underneath as the cause. That applies just as much to a certificate
            // rejection as to a plain read timeout: verified empirically that when the device
            // rejects our certificate, the client's TLS handshake most often completes
            // successfully from startHandshake()'s point of view, and the resulting
            // SSLHandshakeException (certificate_unknown) only surfaces here, on the first
            // post-handshake read - not from TlsSockets.connect() the way UnpairedException
            // in connect() above assumes. Unwrap and classify the real cause through the same
            // rule classify() applies below, so this is reported correctly instead of a bogus
            // corrupt-message ERROR.
            finish(classify(e.getCause()));
        } catch (IOException e) {
            // Reached directly for a write-side failure too (e.g. MessageStream.write() failing
            // to answer a ping after the peer has already closed), not just a read failure -
            // both paths must resolve to the same DisconnectCause for the same underlying cause.
            finish(classify(e));
        }
    }

    /**
     * The one place that decides "retry forever" (STALE/ERROR) versus "tell the user to
     * re-pair" (UNPAIRED) for a read/write failure on this connection. Shared by both the
     * exception thrown directly and the one unwrapped from InvalidProtocolBufferException above,
     * so the rule is defined exactly once.
     */
    private DisconnectCause classify(Throwable cause) {
        if (cause instanceof SocketTimeoutException) {
            return DisconnectCause.STALE;
        } else if (cause instanceof SSLException) {
            return DisconnectCause.UNPAIRED;
        } else if (cause instanceof SocketException) {
            // A reset before the device ever configured us means it rejected the certificate.
            return configured ? DisconnectCause.ERROR : DisconnectCause.UNPAIRED;
        }
        return DisconnectCause.ERROR;
    }

    private void dispatch(RemoteMessage message) throws IOException {
        if (message.hasRemoteConfigure()) {
            write(RemoteMessage.newBuilder()
                    .setRemoteConfigure(RemoteConfigure.newBuilder()
                            .setCode1(CLIENT_FEATURES)
                            .setDeviceInfo(RemoteDeviceInfo.newBuilder()
                                    .setModel("shield-remote")
                                    .setVendor("dev.andre")
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName("dev.andre.shield")
                                    .setAppVersion("0.1.0")))
                    .build());
            configured = true;
        } else if (message.hasRemoteSetActive()) {
            write(RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(CLIENT_FEATURES))
                    .build());
            listener.onReady();
        } else if (message.hasRemotePingRequest()) {
            write(RemoteMessage.newBuilder()
                    .setRemotePingResponse(RemotePingResponse.newBuilder()
                            .setVal1(message.getRemotePingRequest().getVal1()))
                    .build());
        } else if (message.hasRemoteError()) {
            // Error replies may echo app URLs containing credentials. Log only the message types.
            log.warn("Android TV rejected remote message from {}: types={}, error={}",
                    socket.getRemoteSocketAddress(),
                    message.getRemoteError().getMessage().getAllFields().keySet().stream()
                            .map(field -> field.getName()).toList(),
                    message.getRemoteError().getValue());
        } else if (message.hasRemoteStart()) {
            listener.onPower(message.getRemoteStart().getStarted());
        } else if (message.hasRemoteImeKeyInject()) {
            listener.onCurrentApp(message.getRemoteImeKeyInject().getAppInfo().getAppPackage());
        } else if (message.hasRemoteSetVolumeLevel()) {
            RemoteSetVolumeLevel volume = message.getRemoteSetVolumeLevel();
            listener.onVolume(volume.getVolumeLevel(), volume.getVolumeMax(), volume.getVolumeMuted());
        }
        // IME editing, voice, preferred-audio-device traffic is ignored.
    }

    private synchronized void finish(DisconnectCause cause) {
        if (closed) {
            return;
        }
        closed = true;
        closeSocket();
        listener.onDisconnected(cause);
    }

    private void closeSocket() {
        if (Thread.currentThread() != idleWatchdog) {
            idleWatchdog.interrupt();
        }
        try {
            socket.close();
        } catch (IOException _) {
            // Already gone.
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeSocket();
    }

    public static class UnpairedException extends IOException {
        public UnpairedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
