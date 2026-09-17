package dev.andre.homecontrol.adapters.net;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal RFC 6455 server for TV fakes: text frames, fragmentation, ping, close; optional TLS with
 * a self-signed certificate for a host name that is not 127.0.0.1 — exactly what a TV presents.
 */
public final class FakeWebSocketServer implements AutoCloseable {

    @FunctionalInterface
    public interface Handler {
        default void onOpen(Connection connection) {
        }

        void onText(Connection connection, String text);
    }

    public static final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private final String path;
        private final String query;

        Connection(Socket socket, OutputStream out, String path, String query) {
            this.socket = socket;
            this.out = out;
            this.path = path;
            this.query = query;
        }

        public String path() {
            return path;
        }

        /** The raw query string, or null. */
        public String query() {
            return query;
        }

        public void send(String text) {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFrame(int opcode, byte[] payload) {
            try {
                out.write(0x80 | opcode);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length < 65_536) {
                    out.write(126);
                    out.write(payload.length >>> 8);
                    out.write(payload.length & 0xFF);
                } else {
                    out.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) (((long) payload.length >>> shift) & 0xFF));
                    }
                }
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        /**
         * Starts a server-initiated close handshake (status 1000) as a real server does; the socket
         * closes once the client answers. Unlike {@link #close()}, nothing already sent can be lost.
         */
        public void closeNormally() {
            sendFrame(0x8, new byte[]{0x03, (byte) 0xE8});
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final Handler handler;
    private final boolean tls;
    private final List<Connection> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger handshakes = new AtomicInteger();
    private volatile boolean refusing;

    public static FakeWebSocketServer plain(Handler handler) throws IOException {
        return new FakeWebSocketServer(ServerSocketFactory.getDefault(), handler, false);
    }

    public static FakeWebSocketServer tls(Handler handler) throws IOException {
        return new FakeWebSocketServer(tlsContext().getServerSocketFactory(), handler, true);
    }

    private FakeWebSocketServer(ServerSocketFactory factory, Handler handler, boolean tls) throws IOException {
        this.server = factory.createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.handler = handler;
        this.tls = tls;
        Thread.ofVirtual().name("fake-websocket-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public String url(String path) {
        return (tls ? "wss" : "ws") + "://127.0.0.1:" + port() + path;
    }

    /** Completed WebSocket handshakes so far. */
    public int connections() {
        return handshakes.get();
    }

    /** A TV that is switched off: open connections drop, new ones are closed before the handshake. */
    public void refuseConnections(boolean refuse) {
        refusing = refuse;
        if (refuse) {
            dropAll();
        }
    }

    public void dropAll() {
        open.forEach(Connection::close);
        open.clear();
    }

    /** A port on which nothing listens, for "the plain port is closed" tests. */
    public static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            if (refusing) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Refused anyway.
                }
                continue;
            }
            Thread.ofVirtual().name("fake-websocket").start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        Connection connection = null;
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            String key = null;
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                    key = line.substring(colon + 1).trim();
                }
            }
            if (key == null || requestLine.split(" ").length < 2) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String target = requestLine.split(" ")[1];
            int question = target.indexOf('?');
            connection = new Connection(socket, out,
                    question < 0 ? target : target.substring(0, question),
                    question < 0 ? null : target.substring(question + 1));
            open.add(connection);
            handshakes.incrementAndGet();
            handler.onOpen(connection);
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int first = in.read();
                int second = in.read();
                if (first < 0 || second < 0) {
                    return;
                }
                int opcode = first & 0x0F;
                long length = second & 0x7F;
                if (length == 126) {
                    length = ((long) in.read() << 8) | in.read();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | in.read();
                    }
                }
                byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
                byte[] payload = in.readNBytes((int) length);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                switch (opcode) {
                    case 0x0, 0x1 -> {
                        message.write(payload);
                        if ((first & 0x80) != 0) {
                            String text = message.toString(StandardCharsets.UTF_8);
                            message.reset();
                            handler.onText(connection, text);
                        }
                    }
                    case 0x8 -> {
                        connection.sendFrame(0x8, payload);
                        return;
                    }
                    case 0x9 -> connection.sendFrame(0xA, payload);
                    default -> {
                        // Binary and pong frames are not used by the TV protocols.
                    }
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            // The client went away.
        } finally {
            if (connection != null) {
                open.remove(connection);
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) {
            if (c != '\r') {
                line.write(c);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static SSLContext tlsContext() {
        try {
            ClientCertificate identity = ClientCertificate.generate("fake-tv.invalid");
            char[] password = "fake".toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("tv", identity.keyPair().getPrivate(), password, new Certificate[]{identity.certificate()});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, new SecureRandom());
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not build the fake TV's TLS identity", e);
        }
    }

    @Override
    public void close() {
        dropAll();
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }
}
