package dev.andre.homecontrol.adapters.androidtv.protocol;

import dev.andre.homecontrol.adapters.androidtv.protocol.pairing.PairingMessage;
import dev.andre.homecontrol.adapters.androidtv.protocol.pairing.PairingRequest;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                return super.read(b, off, Math.min(len, 1));
            }
        };

        MessageStream reader = new MessageStream(dribble, OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("split-across-tcp-segments");
    }

    @Test
    void readsAMessageWhoseLengthTakesSeveralBytes() throws Exception {
        String link = "https://www.youtube.com/watch?v=" + "x".repeat(300);
        MessageStream reader = new MessageStream(new ByteArrayInputStream(written(request(link))),
                OutputStream.nullOutputStream());

        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName()).isEqualTo(link);
    }

    @Test
    void readsAMessageOfTheLongestLength() throws Exception {
        PairingMessage longest = ofSize(MessageStream.MAX_MESSAGE);
        MessageStream reader = new MessageStream(new ByteArrayInputStream(written(longest)),
                OutputStream.nullOutputStream());

        assertThat(reader.read(PairingMessage.parser())).isEqualTo(longest);
    }

    @Test
    void refusesALongerMessageBeforeReadingItsBody() {
        byte[] prefix = Arrays.copyOf(written(ofSize(MessageStream.MAX_MESSAGE + 1)), 3);
        AtomicLong bodyRead = new AtomicLong();
        InputStream body = new InputStream() {
            @Override
            public int read() {
                bodyRead.incrementAndGet();
                return 0;
            }
        };
        MessageStream reader = new MessageStream(new SequenceInputStream(new ByteArrayInputStream(prefix), body),
                OutputStream.nullOutputStream());

        assertThatThrownBy(() -> reader.read(PairingMessage.parser()))
                .isInstanceOf(InvalidProtocolBufferException.class).hasMessageContaining("exceeds");
        assertThat(bodyRead).hasValue(0);
    }

    @Test
    void refusesTheLongestLengthAVarintCanAnnounce() {
        // 2^31 - 1, which protobuf's own delimited parser would have tried to buffer.
        byte[] wire = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07};
        MessageStream reader = new MessageStream(new ByteArrayInputStream(wire), OutputStream.nullOutputStream());

        assertThatThrownBy(() -> reader.read(PairingMessage.parser())).isInstanceOf(InvalidProtocolBufferException.class);
    }

    @Test
    void aStreamThatEndsInsideAMessageFails() {
        byte[] wire = written(request("cut-short"));
        MessageStream reader = new MessageStream(new ByteArrayInputStream(Arrays.copyOf(wire, wire.length - 1)),
                OutputStream.nullOutputStream());

        assertThatThrownBy(() -> reader.read(PairingMessage.parser())).isInstanceOf(EOFException.class);
    }

    @Test
    void aStreamThatEndsInsideTheLengthFails() {
        MessageStream reader = new MessageStream(new ByteArrayInputStream(new byte[]{(byte) 0x80}),
                OutputStream.nullOutputStream());

        assertThatThrownBy(() -> reader.read(PairingMessage.parser())).isInstanceOf(InvalidProtocolBufferException.class);
    }

    @Test
    void returnsNullAtEndOfStream() throws Exception {
        MessageStream reader = new MessageStream(InputStream.nullInputStream(),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser())).isNull();
    }

    private static byte[] written(PairingMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new MessageStream(InputStream.nullInputStream(), out).write(message);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A message of exactly {@code size} bytes, without its length prefix. */
    private static PairingMessage ofSize(int size) {
        PairingMessage guess = request("x".repeat(size - 100));
        PairingMessage exact = request("x".repeat(size - 100 - (guess.getSerializedSize() - size)));
        assertThat(exact.getSerializedSize()).isEqualTo(size);
        return exact;
    }
}
