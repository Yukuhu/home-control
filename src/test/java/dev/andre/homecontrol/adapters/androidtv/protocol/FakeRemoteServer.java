package dev.andre.homecontrol.adapters.androidtv.protocol;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteAppInfo;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteConfigure;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteImeKeyInject;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteMessage;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemotePingRequest;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteSetActive;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteSetVolumeLevel;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteStart;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final List<Map.Entry<Integer, RemoteDirection>> keyPressesWithDirection = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Integer> pongs = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> appLinks = new LinkedBlockingQueue<>();

    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger connectionsEnded = new AtomicInteger();
    private final AtomicInteger clientConfigureFeatures = new AtomicInteger(-1);
    private final AtomicInteger clientActiveFeatures = new AtomicInteger(-1);

    /**
     * What to do with each of the next connections, in order; anything past the end of the
     * script is served normally. A script rather than a counter so a test can interleave
     * different failures — which is the only way to tell a rule about CONSECUTIVE verdicts
     * from one about a running total.
     */
    private final Queue<ConnectionGate> script = new ConcurrentLinkedQueue<>();

    /** Holds an accepted connection until the test observes the event it needs. */
    public static final class ConnectionGate {
        private final boolean serveAfterRelease;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private ConnectionGate(boolean serveAfterRelease) {
            this.serveAfterRelease = serveAfterRelease;
        }

        public void awaitEntered() throws InterruptedException {
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the scripted connection was never accepted");
            }
        }

        public void release() {
            released.countDown();
        }

        private void awaitRelease() throws InterruptedException {
            entered.countDown();
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the scripted connection");
            }
        }
    }

    private volatile SSLSocket socket;
    private volatile MessageStream stream;
    private volatile ConnectionGate activeGate;

    public FakeRemoteServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(identity),
                new TrustManager[]{TlsSockets.PAIRING_TRUST}, new SecureRandom());
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

    /** Sends part of a frame so tests can hold the client's reader inside a message. */
    public void pushRaw(byte[] bytes) throws IOException {
        synchronized (stream) {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }
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
        for (int i = 0; i < n; i++) {
            ConnectionGate gate = new ConnectionGate(false);
            gate.release();
            script.add(gate);
        }
    }

    /**
     * Accepts the next connection and then never speaks, so the client's TLS handshake fails
     * with a {@link java.net.SocketTimeoutException} — a NETWORK-class failure (spec §8 class 1),
     * not the handshake rejection {@link #closeNextConnections(int)} produces. Release the
     * returned gate after observing that timeout so the server can accept the next connection.
     */
    public ConnectionGate stallNextConnection() {
        ConnectionGate gate = new ConnectionGate(false);
        script.add(gate);
        return gate;
    }

    /**
     * Holds the next handshake until the returned gate is released, then serves it normally,
     * so a test can act while the client is still blocked inside connect().
     */
    public ConnectionGate delayNextConnection() {
        ConnectionGate gate = new ConnectionGate(true);
        script.add(gate);
        return gate;
    }

    /** How many normally served connections have since ended, from either side. */
    public int connectionsEnded() {
        return connectionsEnded.get();
    }

    /** How many times a client has connected; used to observe reconnects. */
    public int connections() {
        return connections.get();
    }

    public int clientConfigureFeatures() {
        return clientConfigureFeatures.get();
    }

    public int clientActiveFeatures() {
        return clientActiveFeatures.get();
    }

    public Integer nextKeyPress() throws InterruptedException {
        return keyPresses.poll(5, TimeUnit.SECONDS);
    }

    /** Every key press received so far, in order, with the direction the client sent it as. */
    public List<Map.Entry<Integer, RemoteDirection>> receivedKeyPresses() {
        return List.copyOf(keyPressesWithDirection);
    }

    public Integer nextPong() throws InterruptedException {
        return pongs.poll(5, TimeUnit.SECONDS);
    }

    public String nextAppLink() throws InterruptedException {
        return appLinks.poll(5, TimeUnit.SECONDS);
    }

    /** Accepts connections in a loop so reconnect behaviour can be tested. */
    private void serve() {
        while (!serverSocket.isClosed()) {
            try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                socket = accepted;
                if (serverSocket.isClosed()) {
                    continue;
                }
                connections.incrementAndGet();
                ConnectionGate gate = script.poll();
                activeGate = gate;
                if (gate != null) {
                    if (serverSocket.isClosed()) {
                        gate.release();
                    }
                    gate.awaitRelease();
                    activeGate = null;
                    if (!gate.serveAfterRelease) {
                        continue;
                    }
                }
                try {
                    handle(accepted);
                } finally {
                    connectionsEnded.incrementAndGet();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception _) {
                // This connection ended; wait for the next one.
            } finally {
                activeGate = null;
            }
        }
    }

    private void handle(SSLSocket connection) throws Exception {
        stream = new MessageStream(connection.getInputStream(), connection.getOutputStream());

        // The device opens the conversation.
        stream.write(RemoteMessage.newBuilder()
                .setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(1)).build());

        RemoteMessage message;
        while ((message = stream.read(RemoteMessage.parser())) != null) {
            if (message.hasRemoteConfigure()) {
                clientConfigureFeatures.set(message.getRemoteConfigure().getCode1());
                stream.write(RemoteMessage.newBuilder()
                        .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622)).build());
            } else if (message.hasRemoteSetActive()) {
                clientActiveFeatures.set(message.getRemoteSetActive().getActive());
                handshakeComplete.countDown();
            } else if (message.hasRemoteKeyInject()) {
                keyPresses.add(message.getRemoteKeyInject().getKeyCodeValue());
                keyPressesWithDirection.add(new AbstractMap.SimpleImmutableEntry<>(
                        message.getRemoteKeyInject().getKeyCodeValue(), message.getRemoteKeyInject().getDirection()));
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
        ConnectionGate gate = activeGate;
        if (gate != null) {
            gate.release();
        }
        if (socket != null) {
            socket.close();
        }
    }
}
