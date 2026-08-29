package dev.andre.shield.protocol;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-delimited protobuf framing, as spoken by the Android TV Remote v2 protocol.
 *
 * <p>The device prefixes every message with a single length byte. Because both pairing
 * and remote messages are always shorter than 128 bytes, that is byte-identical to
 * protobuf's varint delimiting, so the standard delimited APIs are wire-compatible.
 */
public final class MessageStream {

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
        return parser.parseDelimitedFrom(in);
    }
}
