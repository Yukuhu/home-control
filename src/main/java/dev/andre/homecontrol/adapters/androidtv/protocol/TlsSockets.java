package dev.andre.homecontrol.adapters.androidtv.protocol;

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
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * TLS plumbing for both device ports.
 *
 * <p>The device presents a self-signed certificate, so there is no CA to validate
 * against. Pairing first verifies a self-signed certificate and TLS proves possession
 * of its private key; the on-screen pairing exchange establishes the device's identity.
 * Afterwards, the recorded certificate fingerprint is checked during the TLS handshake,
 * before the command channel can process any application messages.
 */
public final class TlsSockets {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final SecureRandom KEY_PASSWORD_RANDOM = new SecureRandom();

    private TlsSockets() {
    }

    /**
     * Opens a TLS connection, keeping the two phases distinguishable to the caller.
     *
     * <p>Reaching the device and being trusted by it are different failures with opposite
     * responses (spec §8): a TCP failure means "unreachable — retry with backoff forever",
     * while a failed handshake can mean the device no longer accepts our certificate.
     * They are told apart here, by which phase threw, and
     * nowhere else: {@link java.net.ConnectException} and {@link java.net.NoRouteToHostException}
     * are {@link SocketException} subclasses, so a caller inspecting the exception type
     * alone cannot tell an unreachable host from a mid-handshake reset.
     *
     * @throws HandshakeRejectedException the TCP connection came up but the TLS handshake failed
     * @throws IOException                the device could not be reached at all
     */
    public static SSLSocket connect(String host, int port, ClientCertificate credential,
                                    int soTimeoutMillis) throws IOException {
        return connect(host, port, credential, soTimeoutMillis, null);
    }

    /**
     * Checks an existing device's pin during the handshake. A null pin is the bootstrap
     * used for pairing and legacy devices without a recorded fingerprint; the caller must
     * authenticate that identity through the pairing exchange before recording a new pin.
     *
     * @throws CertificateMismatchException the peer's certificate does not match the stored pin
     */
    public static SSLSocket connect(String host, int port, ClientCertificate credential,
                                    int soTimeoutMillis, String expectedFingerprint) throws IOException {
        SSLSocket socket;
        try {
            X509TrustManager trustManager = expectedFingerprint == null
                    ? PAIRING_TRUST : new DeviceTrustManager(expectedFingerprint);
            socket = (SSLSocket) context(credential, trustManager)
                    .getSocketFactory().createSocket();
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
            closeAfterFailure(socket, e);
            throw e;
        }

        try {
            socket.startHandshake();
        } catch (SSLException | SocketException e) {
            closeAfterFailure(socket, e);
            if (causedByCertificateMismatch(e)) {
                throw new CertificateMismatchException(
                        "The device at " + host + ":" + port + " presented an unexpected certificate", e);
            }
            // Other handshake failures can mean the device refused our certificate. They can
            // surface either way: as an SSLException (e.g. SSLHandshakeException) if the
            // device sends a TLS alert before closing, or as a SocketException ("broken
            // pipe" / connection reset) if it just resets the connection mid-handshake
            // instead. Verified against a real JSSE server configured to reject the client
            // certificate: the client observed SocketException, not SSLException, because
            // the server's TrustManager rejection closed the accepted socket before the
            // alert was flushed.
            throw new HandshakeRejectedException(
                    "The TLS handshake with " + host + ":" + port + " was rejected", e);
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(socket, e);
            throw e;
        }
        return socket;
    }

    private static void closeAfterFailure(Socket socket, Throwable failure) {
        try {
            socket.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    static SSLContext context(ClientCertificate credential) throws GeneralSecurityException {
        return context(credential, PAIRING_TRUST);
    }

    private static SSLContext context(ClientCertificate credential, X509TrustManager trustManager)
            throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(credential), new TrustManager[]{trustManager}, new SecureRandom());
        return context;
    }

    static KeyManager[] keyManagers(ClientCertificate credential) throws GeneralSecurityException {
        // This password only protects a transient key entry. Persistent credentials retain
        // the configured CertificateStore password, so existing pairings are unaffected.
        char[] password = new char[32];
        for (int i = 0; i < password.length; i++) {
            password[i] = (char) ('!' + KEY_PASSWORD_RANDOM.nextInt(94));
        }
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try {
                keyStore.load(null, null);
            } catch (IOException e) {
                // An in-memory keystore with no input stream; nothing can fail to be read.
                throw new IllegalStateException("Could not initialise an empty keystore", e);
            }
            keyStore.setKeyEntry("client", credential.keyPair().getPrivate(), password,
                    new Certificate[]{credential.certificate()});

            KeyManagerFactory factory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, password);
            return factory.getKeyManagers();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static final X509TrustManager PAIRING_TRUST = new DeviceTrustManager(null);

    private static final class DeviceTrustManager implements X509TrustManager {
        private final String expectedFingerprint;

        private DeviceTrustManager(String expectedFingerprint) {
            this.expectedFingerprint = expectedFingerprint;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verifySelfSigned(peerCertificate(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            X509Certificate certificate = peerCertificate(chain, authType);
            if (expectedFingerprint == null) {
                verifySelfSigned(certificate);
            } else if (!expectedFingerprint.equals(ClientCertificate.fingerprintOf(certificate))) {
                throw new CertificatePinException();
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static X509Certificate peerCertificate(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (chain == null || chain.length == 0 || authType == null || authType.isBlank()) {
            throw new IllegalArgumentException("A certificate chain and authentication type are required");
        }
        if (chain[0] == null) {
            throw new CertificateException("The peer did not provide a certificate");
        }
        return chain[0];
    }

    private static void verifySelfSigned(X509Certificate certificate) throws CertificateException {
        // This proves certificate integrity, not a CA-backed device identity. Pairing's
        // on-screen code authenticates the public key. Do not impose certificate dates:
        // appliances can have stale clocks and keep their paired identity past expiry.
        try {
            certificate.verify(certificate.getPublicKey());
        } catch (GeneralSecurityException e) {
            throw new CertificateException("The peer certificate is not correctly self-signed", e);
        }
    }

    private static boolean causedByCertificateMismatch(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificatePinException) {
                return true;
            }
        }
        return false;
    }

    private static final class CertificatePinException extends CertificateException {
        private CertificatePinException() {
            super("The server certificate does not match the paired device's fingerprint");
        }
    }

    /** A definitive identity mismatch, distinct from the peer refusing our credential. */
    public static class CertificateMismatchException extends IOException {
        public CertificateMismatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
