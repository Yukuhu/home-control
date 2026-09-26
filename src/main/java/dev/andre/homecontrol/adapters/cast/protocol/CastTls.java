package dev.andre.homecontrol.adapters.cast.protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * TLS for port 8009. Receivers present self-signed certificates and senders do not
 * authenticate them (ADR). An {@link X509ExtendedTrustManager} so JSSE adds no hostname or
 * algorithm checks of its own on top.
 */
final class CastTls {

    private CastTls() {
    }

    static SSLSocket connect(String host, int port, int connectTimeoutMillis, int soTimeoutMillis) throws IOException {
        SSLSocket socket;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TRUST_RECEIVER}, new SecureRandom());
            socket = (SSLSocket) context.getSocketFactory().createSocket();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not build the TLS context for " + host + ":" + port, e);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(soTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException _) {
                // Already failing.
            }
            throw e;
        }
    }

    private static final X509ExtendedTrustManager TRUST_RECEIVER = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // Outbound device connections never use this client-certificate callback.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // Device TLS presents self-signed certificates; this protocol intentionally skips CA validation.
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // Outbound device connections never use this client-certificate callback.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // Device TLS presents self-signed certificates; this protocol intentionally skips CA validation.
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Outbound device connections never use this client-certificate callback.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Device TLS presents self-signed certificates; this protocol intentionally skips CA validation.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
