package dev.andre.homecontrol.discovery.ssdp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A loopback stand-in for "every UPnP device on the LAN": answers M-SEARCH unicast for configured targets. */
public class FakeSsdpResponder implements AutoCloseable {

    private final DatagramSocket socket;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final AtomicInteger searches = new AtomicInteger();

    public FakeSsdpResponder() throws IOException {
        this(InetAddress.getLoopbackAddress());
    }

    /** Answers from {@code bindAddress}, so a device announced at 127.0.0.2 is heard from 127.0.0.2. */
    public FakeSsdpResponder(InetAddress bindAddress) throws IOException {
        socket = new DatagramSocket(new InetSocketAddress(bindAddress, 0));
        Thread.ofVirtual().name("fake-ssdp-responder").start(this::serve);
    }

    public String host() {
        return socket.getLocalAddress().getHostAddress();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public int searches() {
        return searches.get();
    }

    public void answer(String searchTarget, String response) {
        responses.put(searchTarget, response);
    }

    public void stopAnswering(String searchTarget) {
        responses.remove(searchTarget);
    }

    /** Datagrams no SSDP parser should accept: not SSDP at all, and a response without a USN. */
    public void sendGarbage(int port) throws IOException {
        InetSocketAddress target = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        for (String text : new String[]{" not ssdp",
                "HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"}) {
            byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes, bytes.length, target));
        }
    }

    /** Loads a fixture, fills in {@code {host}} / {@code {port}}, and normalises to CRLF. */
    public static String fixture(String name, String host, int port) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/ssdp/" + name))
                .replace("{host}", host).replace("{port}", String.valueOf(port))
                .replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private void serve() {
        byte[] buffer = new byte[4096];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException _) {
                return;
            }
            SsdpMessage.parse(packet.getData(), packet.getLength())
                    .filter(message -> message.kind() == SsdpMessage.Kind.SEARCH_REQUEST)
                    .ifPresent(message -> {
                        searches.incrementAndGet();
                        String response = responses.get(message.header("ST").orElse(""));
                        if (response != null) {
                            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                            try {
                                socket.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                            } catch (IOException _) {
                                // The discovery side closed; the test is over.
                            }
                        }
                    });
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
