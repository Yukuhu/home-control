package dev.andre.homecontrol.adapters.androidtv.protocol;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-delimited protobuf framing, as spoken by the Android TV Remote v2 protocol: every message is prefixed with
 * its length as a protobuf varint, byte-identical to protobuf's delimited APIs.
 *
 * <p>A message is read only up to {@link #MAX_MESSAGE} bytes. The device's own messages are a few hundred bytes (an
 * app link is the longest), and protobuf's delimited parser would otherwise buffer whatever length a peer announces,
 * up to 2 GiB.
 */
public final class MessageStream {

    /** The longest message read; a longer length prefix fails the read before any of its body is buffered. */
    static final int MAX_MESSAGE = 64 * 1024;

    private final InputStream in;
    private final OutputStream out;

    public MessageStream(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public synchronized void write(MessageLite message) throws IOException {
        message.writeDelimitedTo(out);
        out.flush();
    }

    /** Returns the next message, or {@code null} if the peer closed the stream cleanly. */
    public <T extends MessageLite> T read(Parser<T> parser) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        int size = CodedInputStream.readRawVarint32(first, in);
        if (size < 0 || size > MAX_MESSAGE) {
            throw new InvalidProtocolBufferException("Message length " + Integer.toUnsignedString(size)
                    + " exceeds " + MAX_MESSAGE + " bytes");
        }
        byte[] body = in.readNBytes(size);
        if (body.length < size) {
            throw new EOFException("The stream ended inside a message");
        }
        return parser.parseFrom(body);
    }
}
