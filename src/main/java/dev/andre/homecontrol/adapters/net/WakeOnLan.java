package dev.andre.homecontrol.adapters.net;

import dev.andre.homecontrol.core.MacAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;

/** Sends the Wake-on-LAN magic packet: six 0xFF bytes, then the MAC sixteen times, as a UDP broadcast. */
public class WakeOnLan {

    private final InetSocketAddress target;

    public WakeOnLan(InetSocketAddress target) {
        this.target = target;
    }

    public static byte[] magicPacket(String mac) {
        byte[] address = MacAddress.bytes(mac);
        byte[] packet = new byte[6 + 16 * 6];
        Arrays.fill(packet, 0, 6, (byte) 0xFF);
        for (int i = 0; i < 16; i++) {
            System.arraycopy(address, 0, packet, 6 + i * 6, 6);
        }
        return packet;
    }

    /** Three copies, because UDP broadcasts get lost and a sleeping NIC may miss the first. */
    public void wake(String mac) throws IOException {
        byte[] packet = magicPacket(mac);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (int i = 0; i < 3; i++) {
                socket.send(new DatagramPacket(packet, packet.length, target));
            }
        }
    }
}
