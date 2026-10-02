package dev.andre.homecontrol.adapters.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A loopback UDP socket standing in for a sleeping TV's network card: records every datagram. */
public final class FakeWakeOnLanReceiver implements AutoCloseable {

    private final DatagramSocket socket;
    private final BlockingQueue<byte[]> packets = new LinkedBlockingQueue<>();
    private final AtomicInteger received = new AtomicInteger();

    public FakeWakeOnLanReceiver() throws IOException {
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread.ofVirtual().name("fake-wake-on-lan").start(this::receive);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port());
    }

    /** The next datagram, or null when none arrives within 5 seconds. */
    public byte[] nextPacket() throws InterruptedException {
        return packets.poll(5, TimeUnit.SECONDS);
    }

    public int received() {
        return received.get();
    }

    private void receive() {
        byte[] buffer = new byte[1024];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException _) {
                return;
            }
            received.incrementAndGet();
            packets.add(Arrays.copyOf(packet.getData(), packet.getLength()));
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
