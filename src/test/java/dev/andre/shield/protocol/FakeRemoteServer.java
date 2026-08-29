package dev.andre.shield.protocol;

import dev.andre.shield.protocol.remote.RemoteAppInfo;
import dev.andre.shield.protocol.remote.RemoteConfigure;
import dev.andre.shield.protocol.remote.RemoteImeKeyInject;
import dev.andre.shield.protocol.remote.RemoteMessage;
import dev.andre.shield.protocol.remote.RemotePingRequest;
import dev.andre.shield.protocol.remote.RemoteSetActive;
import dev.andre.shield.protocol.remote.RemoteSetVolumeLevel;
import dev.andre.shield.protocol.remote.RemoteStart;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import java.security.SecureRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the Shield's command port. Note that the DEVICE drives
 * the handshake: it sends RemoteConfigure first and the client answers.
 */
public class FakeRemoteServer implements AutoCloseable {

    private final ClientCertificate identity = ClientCertificate.generate("fake-shield");
    private final SSLServerSocket serverSocket;
    private final CountDownLatch handshakeComplete = new CountDownLatch(1);

    private final BlockingQueue<Integer> keyPresses = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> appLinks = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> pongs = new LinkedBlockingQueue<>();

    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger connectionsToReject = new AtomicInteger();

    private volatile SSLSocket socket;
    private volatile MessageStream stream;

    public FakeRemoteServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(identity),
                new TrustManager[]{TlsSockets.ACCEPT_ANY}, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
        serverSocket.setWantClientAuth(true);
        Thread.ofVirtual().name("fake-remote-server").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void awaitHandshake() throws InterruptedException {
        if (!handshakeComplete.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the client never completed the handshake");
        }
    }

    public void pushPower(boolean on) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteStart(RemoteStart.newBuilder().setStarted(on)).build());
    }

    public void pushVolume(int level, int max, boolean muted) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteSetVolumeLevel(RemoteSetVolumeLevel.newBuilder()
                        .setVolumeLevel(level).setVolumeMax(max).setVolumeMuted(muted)).build());
    }

    public void pushCurrentApp(String appPackage) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteImeKeyInject(RemoteImeKeyInject.newBuilder()
                        .setAppInfo(RemoteAppInfo.newBuilder().setAppPackage(appPackage))).build());
    }

    public void pushPing(int value) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(value)).build());
    }

    public void hangUp() throws Exception {
        socket.close();
    }

    /**
     * For each of the next {@code n} connections accepted, closes the socket immediately
     * without performing the app-level (RemoteConfigure/SetActive) exchange, then serves
     * connection {@code n + 1} onward normally. Deterministic stand-in for a device that
     * drops the connection before the handshake completes — no timing window to hit,
     * unlike racing {@link #hangUp()} against the real exchange.
     */
    public void closeNextConnections(int n) {
        connectionsToReject.set(n);
    }

    /** How many times a client has connected; used to observe reconnects. */
    public int connections() {
        return connections.get();
    }

    public Integer nextKeyPress() throws InterruptedException {
        return keyPresses.poll(5, TimeUnit.SECONDS);
    }

    public String nextAppLink() throws InterruptedException {
        return appLinks.poll(5, TimeUnit.SECONDS);
    }

    public Integer nextPong() throws InterruptedException {
        return pongs.poll(5, TimeUnit.SECONDS);
    }

    /** Accepts connections in a loop so reconnect behaviour can be tested. */
    private void serve() {
        while (!serverSocket.isClosed()) {
            try {
                socket = (SSLSocket) serverSocket.accept();
                connections.incrementAndGet();
                if (shouldRejectThisConnection()) {
                    socket.close();
                    continue;
                }
                handle(socket);
            } catch (Exception e) {
                // This connection ended; wait for the next one.
            }
        }
    }

    /** Atomically consumes one unit of the {@link #closeNextConnections(int)} budget, if any is left. */
    private boolean shouldRejectThisConnection() {
        return connectionsToReject.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0;
    }

    private void handle(SSLSocket connection) throws Exception {
        stream = new MessageStream(connection.getInputStream(), connection.getOutputStream());

        // The device opens the conversation.
        stream.write(RemoteMessage.newBuilder()
                .setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(1)).build());

        RemoteMessage message;
        while ((message = stream.read(RemoteMessage.parser())) != null) {
            if (message.hasRemoteConfigure()) {
                stream.write(RemoteMessage.newBuilder()
                        .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622)).build());
            } else if (message.hasRemoteSetActive()) {
                handshakeComplete.countDown();
            } else if (message.hasRemoteKeyInject()) {
                if (message.getRemoteKeyInject().getDirectionValue() != 3) {
                    throw new IllegalStateException("expected SHORT direction");
                }
                keyPresses.add(message.getRemoteKeyInject().getKeyCodeValue());
            } else if (message.hasRemoteAppLinkLaunchRequest()) {
                appLinks.add(message.getRemoteAppLinkLaunchRequest().getAppLink());
            } else if (message.hasRemotePingResponse()) {
                pongs.add(message.getRemotePingResponse().getVal1());
            }
        }
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
        if (socket != null) {
            socket.close();
        }
    }
}
