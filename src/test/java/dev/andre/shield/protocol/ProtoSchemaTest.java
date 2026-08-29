package dev.andre.shield.protocol;

import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingRequest;
import dev.andre.shield.protocol.remote.RemoteDirection;
import dev.andre.shield.protocol.remote.RemoteKeyCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoSchemaTest {

    @Test
    void pairingMessageRoundTripsThroughDelimitedEncoding() throws Exception {
        PairingMessage sent = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(PairingRequest.newBuilder()
                        .setServiceName("shield-remote")
                        .setClientName("shield-remote"))
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sent.writeDelimitedTo(out);
        byte[] wire = out.toByteArray();

        // Pairing messages are always < 128 bytes, so the varint prefix is one byte
        // holding the payload length -- byte-identical to the device's framing.
        assertThat(wire[0]).isEqualTo((byte) (wire.length - 1));

        PairingMessage parsed = PairingMessage.parseDelimitedFrom(new ByteArrayInputStream(wire));
        assertThat(parsed.getPairingRequest().getServiceName()).isEqualTo("shield-remote");
        assertThat(parsed.getStatus()).isEqualTo(PairingMessage.Status.STATUS_OK);
    }

    @Test
    void remoteEnumsHaveTheVerifiedWireValues() {
        assertThat(RemoteKeyCode.KEYCODE_DPAD_UP.getNumber()).isEqualTo(19);
        assertThat(RemoteKeyCode.KEYCODE_DPAD_CENTER.getNumber()).isEqualTo(23);
        assertThat(RemoteKeyCode.KEYCODE_POWER.getNumber()).isEqualTo(26);
        assertThat(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE.getNumber()).isEqualTo(85);
        assertThat(RemoteKeyCode.KEYCODE_VOLUME_UP.getNumber()).isEqualTo(24);
        assertThat(RemoteDirection.SHORT.getNumber()).isEqualTo(3);
    }
}
