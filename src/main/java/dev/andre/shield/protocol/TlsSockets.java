package dev.andre.shield.protocol;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.net.InetSocketAddress;
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

    public static SSLSocket connect(String host, int port, ClientCertificate credential,
                                    int soTimeoutMillis) throws IOException {
        try {
            SSLContext context = context(credential);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(soTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            return socket;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not open a TLS connection to " + host + ":" + port, e);
        }
    }

    static SSLContext context(ClientCertificate credential) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(credential), new TrustManager[]{ACCEPT_ANY}, new SecureRandom());
        return context;
    }

    static KeyManager[] keyManagers(ClientCertificate credential) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, KEYSTORE_PASSWORD);
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
}
