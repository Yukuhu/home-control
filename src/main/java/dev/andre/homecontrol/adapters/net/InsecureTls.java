package dev.andre.homecontrol.adapters.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * TVs serve their LAN APIs over TLS with self-signed certificates for names that are not their IP.
 * Clients from here accept any certificate and skip host name checks; being an
 * {@link X509ExtendedTrustManager} is what makes JSSE skip endpoint identification too.
 * Use only for TV connections on the LAN; never make this the default context.
 */
public final class InsecureTls {

    private InsecureTls() {
    }

    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .sslContext(trustingAnyCertificate())
                .connectTimeout(connectTimeout)
                .build();
    }

    static SSLContext trustingAnyCertificate() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new AcceptAny()}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is unavailable", e);
        }
    }

    private static final class AcceptAny extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

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
    }
}
