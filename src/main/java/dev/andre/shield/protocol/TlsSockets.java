package dev.andre.shield.protocol;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * TLS plumbing for both device ports.
 *
 * <p>The device presents a self-signed certificate, so there is no CA to validate
 * against: identity is established by the pairing exchange and, afterwards, by
 * pinning the certificate recorded at pairing time.
 */
public final class TlsSockets {

    private static final char[] KEYSTORE_PASSWORD = "shield".toCharArray();
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private TlsSockets() {
    }

    /**
     * Opens a TLS connection, keeping the two phases distinguishable to the caller.
     *
     * <p>Reaching the device and being trusted by it are different failures with opposite
     * responses (spec §8): a TCP failure means "unreachable — retry with backoff forever",
     * while a failed handshake means "the device no longer accepts our certificate — stop
     * and tell the user to re-pair". They are told apart here, by which phase threw, and
     * nowhere else: {@link java.net.ConnectException} and {@link java.net.NoRouteToHostException}
     * are {@link SocketException} subclasses, so a caller inspecting the exception type
     * alone cannot tell an unreachable host from a mid-handshake reset.
     *
     * @throws HandshakeRejectedException the TCP connection came up but the TLS handshake
     *                                    failed in a way that means the device rejected us
     * @throws IOException                the device could not be reached at all
     */
    public static SSLSocket connect(String host, int port, ClientCertificate credential,
                                    int soTimeoutMillis) throws IOException {
        SSLSocket socket;
        try {
            socket = (SSLSocket) context(credential).getSocketFactory().createSocket();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not build the TLS context for " + host + ":" + port, e);
        }

        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(soTimeoutMillis);
            socket.setTcpNoDelay(true);
        } catch (IOException | RuntimeException e) {
            // ConnectException, NoRouteToHostException, SocketTimeoutException,
            // UnknownHostException: the device is unreachable, which says nothing about
            // whether it still trusts us. Surfaced unchanged so the caller retries.
            closeQuietly(socket);
            throw e;
        }

        try {
            socket.startHandshake();
        } catch (SSLException | SocketException e) {
            // Only here does a TLS failure mean the device refused our certificate. It can
            // surface either way: as an SSLException (e.g. SSLHandshakeException) if the
            // device sends a TLS alert before closing, or as a SocketException ("broken
            // pipe" / connection reset) if it just resets the connection mid-handshake
            // instead. Verified against a real JSSE server configured to reject the client
            // certificate: the client observed SocketException, not SSLException, because
            // the server's TrustManager rejection closed the accepted socket before the
            // alert was flushed.
            closeQuietly(socket);
            throw new HandshakeRejectedException(
                    "The TLS handshake with " + host + ":" + port + " was rejected", e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
        return socket;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to salvage; the caller is already handling a failure.
        }
    }

    static SSLContext context(ClientCertificate credential) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(credential), new TrustManager[]{ACCEPT_ANY}, new SecureRandom());
        return context;
    }

    static KeyManager[] keyManagers(ClientCertificate credential) throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try {
            keyStore.load(null, KEYSTORE_PASSWORD);
        } catch (IOException e) {
            // An in-memory keystore with no input stream; nothing can fail to be read.
            throw new IllegalStateException("Could not initialise an empty keystore", e);
        }
        keyStore.setKeyEntry("client", credential.keyPair().getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{credential.certificate()});

        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, KEYSTORE_PASSWORD);
        return factory.getKeyManagers();
    }

    static final X509TrustManager ACCEPT_ANY = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /**
     * Thrown only for a failure of the TLS handshake itself, never for failing to reach the
     * host. A distinct type so callers that map "the device refused our certificate" onto
     * their own vocabulary cannot accidentally catch a connect-phase failure too.
     */
    public static class HandshakeRejectedException extends IOException {
        public HandshakeRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
