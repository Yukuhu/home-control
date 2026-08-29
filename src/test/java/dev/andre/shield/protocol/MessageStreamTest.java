package dev.andre.shield.protocol;

import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStreamTest {

    private static PairingMessage request(String name) {
        return PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(PairingRequest.newBuilder().setServiceName(name))
                .build();
    }

    @Test
    void writesAndReadsBackASingleMessage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MessageStream(InputStream.nullInputStream(), out).write(request("first"));

        MessageStream reader = new MessageStream(new ByteArrayInputStream(out.toByteArray()),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("first");
    }

    @Test
    void readsTwoMessagesDeliveredInOneChunk() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageStream writer = new MessageStream(InputStream.nullInputStream(), out);
        writer.write(request("first"));
        writer.write(request("second"));

        MessageStream reader = new MessageStream(new ByteArrayInputStream(out.toByteArray()),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("first");
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("second");
    }

    @Test
    void reassemblesAMessageSplitAcrossReads() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MessageStream(InputStream.nullInputStream(), out).write(request("split-across-tcp-segments"));
        byte[] wire = out.toByteArray();

        // A stream that hands over one byte at a time, as a slow TCP connection would.
        InputStream dribble = new ByteArrayInputStream(wire) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, 1);
            }
        };

        MessageStream reader = new MessageStream(dribble, OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("split-across-tcp-segments");
    }

    @Test
    void returnsNullAtEndOfStream() throws Exception {
        MessageStream reader = new MessageStream(InputStream.nullInputStream(),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser())).isNull();
    }
}
