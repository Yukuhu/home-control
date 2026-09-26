package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WakeOnLanTest {

    private static final byte[] MAC = {(byte) 0xA8, 0x23, (byte) 0xFE, 0x01, 0x02, 0x03};

    @Test
    void theMagicPacketIsSixFfBytesAndTheMacSixteenTimes() {
        byte[] packet = WakeOnLan.magicPacket("A8:23:FE:01:02:03");

        assertThat(packet).hasSize(102);
        assertThat(Arrays.copyOfRange(packet, 0, 6)).containsOnly((byte) 0xFF);
        for (int i = 0; i < 16; i++) {
            assertThat(Arrays.copyOfRange(packet, 6 + 6 * i, 12 + 6 * i)).containsExactly(MAC);
        }
    }

    @Test
    void sendsThreePacketsToTheTarget() throws Exception {
        try (FakeWakeOnLanReceiver receiver = new FakeWakeOnLanReceiver()) {
            new WakeOnLan(receiver.address()).wake("a8-23-fe-01-02-03");

            byte[] expected = WakeOnLan.magicPacket("A8:23:FE:01:02:03");
            for (int i = 0; i < 3; i++) {
                assertThat(receiver.nextPacket()).containsExactly(expected);
            }
        }
    }

    @Test
    void anInvalidMacIsRejectedBeforeSending() throws Exception {
        try (FakeWakeOnLanReceiver receiver = new FakeWakeOnLanReceiver()) {
            var preparedReceiver40 = new WakeOnLan(receiver.address());
            assertThatThrownBy(() -> preparedReceiver40.wake("nope"))
                    .isInstanceOf(IllegalArgumentException.class);

            Thread.sleep(500);
            assertThat(receiver.received()).isZero();
        }
    }
}
