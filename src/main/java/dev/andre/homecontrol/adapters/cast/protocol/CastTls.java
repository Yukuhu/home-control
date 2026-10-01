package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.InsecureTls;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * TLS for port 8009. Receivers present self-signed certificates and senders do not authenticate them
 * (docs/adr/0001-cast-sender.md), so the socket comes from {@link InsecureTls}'s trust-any context, which also adds no
 * hostname or algorithm checks of its own.
 */
final class CastTls {

    private CastTls() {
    }

    static SSLSocket connect(String host, int port, int connectTimeoutMillis, int soTimeoutMillis) throws IOException {
        SSLSocket socket = (SSLSocket) InsecureTls.trustingAnyCertificate().getSocketFactory().createSocket();
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
}
