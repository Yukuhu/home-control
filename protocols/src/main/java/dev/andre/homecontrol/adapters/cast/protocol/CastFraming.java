package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * CASTV2 framing: a 4-byte big-endian length, then that many bytes of {@link CastMessage}.
 * Not protobuf's varint delimiting — the Remote v2 {@code MessageStream} does not apply here.
 */
public final class CastFraming {

    /** Chromium caps a message at 65 535 bytes; anything larger is a broken or hostile peer. */
    public static final int MAX_MESSAGE_BYTES = 65_536;

    private final InputStream in;
    private final OutputStream out;

    public CastFraming(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** One write call per frame so concurrent writers can never interleave header and body. */
    public synchronized void write(CastMessage message) throws IOException {
        byte[] body = message.toByteArray();
        out.write(ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array());
        out.flush();
    }

    /** Returns the next message, or {@code null} if the peer closed the stream between frames. */
    public CastMessage read() throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        byte[] rest = in.readNBytes(3);
        if (rest.length < 3) {
            throw new EOFException("The Cast peer closed the stream inside a frame header");
        }
        long length = ((long) first << 24) | ((rest[0] & 0xFFL) << 16) | ((rest[1] & 0xFFL) << 8) | (rest[2] & 0xFFL);
        if (length > MAX_MESSAGE_BYTES) {
            throw new IOException("A Cast frame of " + length + " bytes exceeds the limit of " + MAX_MESSAGE_BYTES);
        }
        byte[] body = in.readNBytes((int) length);
        if (body.length < length) {
            throw new EOFException("The Cast peer closed the stream inside a frame");
        }
        return CastMessage.parseFrom(body);
    }
}
