package dev.andre.shield.protocol;

import dev.andre.shield.protocol.pairing.PairingMessage;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A device that completes the TLS handshake and then refuses the very first pairing step,
 * so {@link PairingSession#start()} fails with the socket already open.
 *
 * <p>It then keeps reading, which is how {@link #clientHungUp()} can tell a closed client
 * socket (end of stream, or a reset) from a leaked one (the read simply never returns).
 */
public class RefusingPairingServer implements AutoCloseable {

    private final SSLServerSocket serverSocket;
    private final CountDownLatch hungUp = new CountDownLatch(1);

    public RefusingPairingServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(ClientCertificate.generate("refusing-fake-shield")),
                new TrustManager[]{TlsSockets.ACCEPT_ANY}, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
        serverSocket.setWantClientAuth(true);
        Thread.ofVirtual().name("refusing-fake-shield").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Whether the client closed its end within {@code timeoutSeconds}. */
    public boolean clientHungUp(int timeoutSeconds) throws InterruptedException {
        return hungUp.await(timeoutSeconds, TimeUnit.SECONDS);
    }

    private void serve() {
        try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
            MessageStream stream = new MessageStream(
                    accepted.getInputStream(), accepted.getOutputStream());

            stream.read(PairingMessage.parser());
            stream.write(PairingMessage.newBuilder()
                    .setProtocolVersion(2)
                    .setStatus(PairingMessage.Status.STATUS_BAD_CONFIGURATION)
                    .build());

            if (stream.read(PairingMessage.parser()) == null) {
                hungUp.countDown();
            }
        } catch (Exception e) {
            // A reset instead of a clean close is the client hanging up too.
            hungUp.countDown();
        }
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
    }
}
