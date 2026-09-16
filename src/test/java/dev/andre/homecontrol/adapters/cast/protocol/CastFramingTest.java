package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastFramingTest {

    private static CastMessage ping() {
        return CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId("sender-0")
                .setDestinationId("receiver-0")
                .setNamespace(CastNamespaces.HEARTBEAT)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8("{\"type\":\"PING\"}")
                .build();
    }

    @Test
    void prefixesEachMessageWithItsLengthAsFourBigEndianBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new CastFraming(new ByteArrayInputStream(new byte[0]), out).write(ping());

        byte[] wire = out.toByteArray();
        byte[] body = ping().toByteArray();
        assertThat(ByteBuffer.wrap(wire, 0, 4).getInt()).isEqualTo(body.length);
        assertThat(Arrays.copyOfRange(wire, 4, wire.length)).isEqualTo(body);
    }

    @Test
    void readsBackWhatItWroteAndReportsACleanEndAsNull() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastFraming writer = new CastFraming(new ByteArrayInputStream(new byte[0]), out);
        writer.write(ping());
        writer.write(ping());

        CastFraming reader = new CastFraming(new ByteArrayInputStream(out.toByteArray()), OutputStream.nullOutputStream());

        assertThat(reader.read()).isEqualTo(ping());
        assertThat(reader.read()).isEqualTo(ping());
        assertThat(reader.read()).isNull();
    }

    @Test
    void aTruncatedFrameIsAnError() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CastFraming(new ByteArrayInputStream(new byte[0]), out).write(ping());
        byte[] truncated = Arrays.copyOf(out.toByteArray(), out.size() - 3);

        CastFraming reader = new CastFraming(new ByteArrayInputStream(truncated), OutputStream.nullOutputStream());

        assertThatThrownBy(reader::read).isInstanceOf(EOFException.class);
    }

    @Test
    void refusesAFrameLargerThanTheLimit() {
        byte[] header = ByteBuffer.allocate(4).putInt(CastFraming.MAX_MESSAGE_BYTES + 1).array();

        CastFraming reader = new CastFraming(new ByteArrayInputStream(header), OutputStream.nullOutputStream());

        assertThatThrownBy(reader::read).isInstanceOf(IOException.class).hasMessageContaining("limit");
    }
}
