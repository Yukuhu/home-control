package dev.andre.shield.protocol;

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
        } catch (SSLException e) {
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
        } catch (SocketTimeoutException e) {
            finish(DisconnectCause.STALE);
        } catch (SSLException e) {
            finish(DisconnectCause.UNPAIRED);
        } catch (SocketException e) {
            // A reset before the device ever configured us means it rejected the certificate.
            finish(configured ? DisconnectCause.ERROR : DisconnectCause.UNPAIRED);
        } catch (IOException e) {
            finish(DisconnectCause.ERROR);
        }
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
