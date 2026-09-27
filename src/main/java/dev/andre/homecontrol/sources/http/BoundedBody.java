package dev.andre.homecontrol.sources.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * A response body for {@link java.net.http.HttpClient}, bounded in size and in time. The JDK's request timeout
 * ends once the response headers arrive, so without this a server that trickles its body could hold the caller,
 * and the rail fetch permit it holds, for as long as it likes.
 */
public final class BoundedBody {

    private BoundedBody() {
    }

    /**
     * Keeps at most {@code maxBytes + 1} bytes, so a caller can tell an oversized body apart without buffering all
     * of it, and makes {@code send} throw {@link HttpTimeoutException} when the whole exchange has not finished
     * within {@code timeout} of this call. Create it right before {@code send}.
     */
    public static HttpResponse.BodyHandler<byte[]> handler(int maxBytes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return info -> new Subscriber(maxBytes + 1L, deadline);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final long deadline;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        Subscriber(long limit, long deadline) {
            this.limit = limit;
            this.deadline = deadline;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            long remaining = Math.max(0, deadline - System.nanoTime());
            CompletableFuture.delayedExecutor(remaining, TimeUnit.NANOSECONDS).execute(this::expire);
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            boolean cappedOut;
            synchronized (this) {
                if (body.isDone()) {
                    return;
                }
                for (ByteBuffer buffer : buffers) {
                    int take = (int) Math.min(buffer.remaining(), limit - bytes.size());
                    byte[] chunk = new byte[take];
                    buffer.get(chunk);
                    bytes.write(chunk, 0, take);
                }
                cappedOut = bytes.size() >= limit;
                if (cappedOut) {
                    finish();
                } else {
                    // A request after cancel is a no-op and cancel is idempotent, so request(1) stays inside
                    // the monitor while cancel() (below) is called outside it.
                    subscription.request(1);
                }
            }
            if (cappedOut) {
                subscription.cancel();
            }
        }

        @Override
        public synchronized void onError(Throwable error) {
            bytes = null;
            body.completeExceptionally(error);
        }

        @Override
        public synchronized void onComplete() {
            if (!body.isDone()) {
                finish();
            }
        }

        /**
         * Completes first, then cancels: cancelling first lets the client fail the exchange with its own error.
         * The decision (and the completion) is made under the monitor; {@code cancel()} runs after it is released.
         */
        private void expire() {
            boolean timedOut;
            synchronized (this) {
                timedOut = !body.isDone();
                if (timedOut) {
                    bytes = null;
                    body.completeExceptionally(new HttpTimeoutException("request timed out"));
                }
            }
            if (timedOut) {
                subscription.cancel();
            }
        }

        /** Hands the bytes over and drops the buffer: the pending expiry keeps this subscriber until the deadline. */
        private void finish() {
            byte[] result = bytes.toByteArray();
            bytes = null;
            body.complete(result);
        }
    }
}
