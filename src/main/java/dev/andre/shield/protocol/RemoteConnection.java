package dev.andre.shield.protocol;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.andre.shield.protocol.remote.RemoteAppLinkLaunchRequest;
import dev.andre.shield.protocol.remote.RemoteConfigure;
import dev.andre.shield.protocol.remote.RemoteDeviceInfo;
import dev.andre.shield.protocol.remote.RemoteDirection;
import dev.andre.shield.protocol.remote.RemoteKeyCode;
import dev.andre.shield.protocol.remote.RemoteKeyInject;
import dev.andre.shield.protocol.remote.RemoteMessage;
import dev.andre.shield.protocol.remote.RemotePingResponse;
import dev.andre.shield.protocol.remote.RemoteSetActive;
import dev.andre.shield.protocol.remote.RemoteSetVolumeLevel;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;

/**
 * The long-lived command channel on port 6466.
 *
 * <p>The device drives the handshake and then pushes state unprompted; a reader thread
 * answers its pings and forwards everything else to the {@link RemoteListener}.
 */
public class RemoteConnection implements AutoCloseable {

    /** The magic number the reference implementation sends for configure and set-active. */
    private static final int ACTIVE_CODE = 622;

    private final SSLSocket socket;
    private final MessageStream stream;
    private final RemoteListener listener;
    private final X509Certificate serverCertificate;

    private volatile boolean configured;
    private volatile boolean closed;

    public static RemoteConnection connect(String host, int port, ClientCertificate credential,
                                           int staleTimeoutMillis, RemoteListener listener)
            throws IOException {
        SSLSocket socket;
        try {
            socket = TlsSockets.connect(host, port, credential, staleTimeoutMillis);
        } catch (TlsSockets.HandshakeRejectedException e) {
            // Only the HANDSHAKE phase means "the device refused our certificate"; retrying
            // with the same certificate is pointless, so it maps to UnpairedException.
            // TlsSockets decides which phase a failure came from and is the only place that
            // can: a connect-phase ConnectException is itself a SocketException, so catching
            // SSLException/SocketException here would classify an unreachable or rebooting
            // device as unpaired and stop it from ever being retried (spec §8 class 1).
            // Every other IOException from connect() is a network failure and passes through.
            throw new UnpairedException("the device refused our certificate", e);
        }
        return new RemoteConnection(socket, listener);
    }

    private RemoteConnection(SSLSocket socket, RemoteListener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
        this.serverCertificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        Thread.ofVirtual().name("shield-remote-reader").start(this::readLoop);
    }

    /** The certificate the device presented, for the caller to compare against its pin. */
    public X509Certificate serverCertificate() {
        return serverCertificate;
    }

    public void sendKey(RemoteKey key) throws IOException {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteKeyInject(RemoteKeyInject.newBuilder()
                        .setKeyCode(RemoteKeyCode.forNumber(key.code()))
                        .setDirection(RemoteDirection.SHORT))
                .build());
    }

    public void launchAppLink(String uri) throws IOException {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteAppLinkLaunchRequest(RemoteAppLinkLaunchRequest.newBuilder().setAppLink(uri))
                .build());
    }

    private void readLoop() {
        try {
            RemoteMessage message;
            while (!closed && (message = stream.read(RemoteMessage.parser())) != null) {
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
            stream.write(RemoteMessage.newBuilder()
                    .setRemoteConfigure(RemoteConfigure.newBuilder()
                            .setCode1(ACTIVE_CODE)
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
            stream.write(RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(ACTIVE_CODE))
                    .build());
        } else if (message.hasRemotePingRequest()) {
            stream.write(RemoteMessage.newBuilder()
                    .setRemotePingResponse(RemotePingResponse.newBuilder()
                            .setVal1(message.getRemotePingRequest().getVal1()))
                    .build());
        } else if (message.hasRemoteStart()) {
            listener.onPower(message.getRemoteStart().getStarted());
        } else if (message.hasRemoteImeKeyInject()) {
            listener.onCurrentApp(message.getRemoteImeKeyInject().getAppInfo().getAppPackage());
        } else if (message.hasRemoteSetVolumeLevel()) {
            RemoteSetVolumeLevel volume = message.getRemoteSetVolumeLevel();
            listener.onVolume(volume.getVolumeLevel(), volume.getVolumeMax(), volume.getVolumeMuted());
        }
        // IME editing, voice, preferred-audio-device and RemoteError traffic is ignored.
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
        try {
            socket.close();
        } catch (IOException ignored) {
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
