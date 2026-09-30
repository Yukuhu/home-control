package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The one way a content source reaches the network. It connects only to addresses its {@link OutboundAddressPolicy}
 * approved, looked up once, so a host cannot pass the check at one address and be reached at another. One deadline
 * covers waiting for a slot, DNS, connecting, every redirect and the whole body; each exchange runs on a worker that
 * is cancelled at the deadline. Bodies are capped (Content-Length first) and never decompressed. Failures reach the
 * source's factory as an {@link OutboundFailure}, which names only the host and carries no cause.
 */
public final class GuardedHttpClient implements AutoCloseable {

    /** Which redirects a GET follows: none (the 3xx is returned), within the origin, or anywhere, re-checked. */
    public enum Redirects { NONE, SAME_ORIGIN, CHECKED }

    /**
     * How one source uses the network.
     *
     * @param name     the source as running text calls it, e.g. "the calendar"
     * @param urlRules how a redirect's {@code Location} is parsed
     */
    public record Profile(String name, Redirects redirects, int maxRedirects, int maxBytes, Duration connectTimeout,
                          Duration deadline, int maxConcurrent, HttpUrls.Rules urlRules) {
        public Profile {
            Objects.requireNonNull(name);
            Objects.requireNonNull(redirects);
            Objects.requireNonNull(connectTimeout);
            Objects.requireNonNull(deadline);
            Objects.requireNonNull(urlRules);
            if (maxRedirects < 0 || maxBytes < 0 || maxBytes == Integer.MAX_VALUE || maxConcurrent < 1) {
                throw new IllegalArgumentException("Invalid profile for " + name);
            }
        }
    }

    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final Profile profile;
    private final OutboundAddressPolicy policy;
    private final Function<OutboundFailure, ? extends RuntimeException> failures;
    private final Semaphore permits;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final CloseableHttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Exchange<?>> exchanges = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Exchange<?>> current = new ThreadLocal<>();

    public GuardedHttpClient(Profile profile, OutboundAddressPolicy policy,
                             Function<OutboundFailure, ? extends RuntimeException> failures) {
        this.profile = Objects.requireNonNull(profile);
        this.policy = Objects.requireNonNull(policy);
        this.failures = Objects.requireNonNull(failures);
        permits = new Semaphore(profile.maxConcurrent());
        http = VettedHttpClients.create(host -> {
            Exchange<?> exchange = current.get();
            exchange.check();
            InetAddress[] addresses = policy.addresses(host);
            // A platform resolver can ignore interruption. Never connect after it returns late.
            exchange.check();
            return addresses;
        }, profile.maxConcurrent(), profile.connectTimeout());
    }

    /** Sends the request and returns any status; a redirect is followed as the profile says. */
    public OutboundResponse send(OutboundRequest request) {
        return run(request, () -> exchange(request));
    }

    /** Looks the request's host up through the policy, under the same slots and deadline, without connecting. */
    public void vet(OutboundRequest request) {
        run(request, () -> {
            policy.addresses(host(request.uri()));
            current.get().check();
            return null;
        });
    }

    private <T> T run(OutboundRequest request, Callable<T> work) {
        String host = host(request.uri());
        if (closed.get()) {
            throw failures.apply(unreachable(host, OutboundFailure.CLOSED));
        }
        if (Thread.currentThread().isInterrupted()) {
            // Shutting down: keep the interrupt for the caller and open no new connection.
            throw failures.apply(unreachable(host, OutboundFailure.INTERRUPTED));
        }
        long sent = System.nanoTime();
        long ownDeadline = sent + profile.deadline().toNanos();
        long waitUntil = request.notAfter().orElse(ownDeadline);
        admit(request, host, waitUntil);
        long deadline = request.notAfter().isEmpty() ? ownDeadline
                : earlier(request.notAfter().getAsLong(), System.nanoTime() + profile.deadline().toNanos());
        var exchange = new Exchange<T>(host, deadline);
        exchanges.add(exchange);
        try {
            workers.execute(() -> runOnWorker(exchange, work));
        } catch (RejectedExecutionException _) {
            exchanges.remove(exchange);
            permits.release();
            throw failures.apply(unreachable(host, OutboundFailure.CLOSED));
        }
        try {
            return exchange.result.get(exchange.remaining(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            exchange.cancel();
            Thread.currentThread().interrupt();
            throw failures.apply(unreachable(host, OutboundFailure.INTERRUPTED));
        } catch (TimeoutException _) {
            exchange.cancel();
            throw failures.apply(unreachable(host, OutboundFailure.TIMED_OUT));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Failed failed) {
                throw failures.apply(failed.failure);
            }
            throw failures.apply(unreachable(host, OutboundFailure.FAILED));
        }
    }

    private void admit(OutboundRequest request, String host, long waitUntil) {
        try {
            long remaining = waitUntil - System.nanoTime();
            boolean admitted = request.waitForSlot()
                    ? remaining > 0 && permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)
                    : permits.tryAcquire();
            if (!admitted) {
                throw failures.apply(new OutboundFailure(Kind.RATE_LIMITED, host, OutboundFailure.BUSY, 0));
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw failures.apply(unreachable(host, OutboundFailure.INTERRUPTED));
        }
    }

    /** Runs on the worker; its caller hears the outcome once the slot is released. */
    private <T> void runOnWorker(Exchange<T> exchange, Callable<T> work) {
        T value = null;
        // Replaced by the outcome. Left as is only when an Error escapes to the thread's handler,
        // so the caller still hears at once that the request failed instead of waiting out its deadline.
        Exception error = new Failed(unreachable(exchange.host, OutboundFailure.FAILED));
        exchange.worker = Thread.currentThread();
        current.set(exchange);
        try {
            exchange.check();
            value = work.call();
            exchange.check();
            error = null;
        } catch (Failed e) {
            error = e;
        } catch (IOException e) {
            error = new Failed(failureOf(exchange, e));
        } catch (Exception _) {
            error = new Failed(unreachable(exchange.host, exchange.expired() ? OutboundFailure.TIMED_OUT
                    : OutboundFailure.FAILED));
        } finally {
            current.remove();
            exchanges.remove(exchange);
            // Only the worker releases its slot, even when its caller has already given up.
            permits.release();
            if (error == null) {
                exchange.result.complete(value);
            } else {
                exchange.result.completeExceptionally(error);
            }
        }
    }

    private OutboundFailure failureOf(Exchange<?> exchange, IOException e) {
        String host = exchange.host;
        if (closed.get()) {
            return unreachable(host, OutboundFailure.CLOSED);
        }
        if (e instanceof OutboundAddressPolicy.BlockedAddressException) {
            return new OutboundFailure(Kind.BLOCKED, host, OutboundFailure.ADDRESS_NOT_ALLOWED, 0);
        }
        if (e instanceof UnknownHostException) {
            return unreachable(host, OutboundFailure.UNKNOWN_HOST);
        }
        if (exchange.expired() || e instanceof InterruptedIOException) {
            return unreachable(host, OutboundFailure.TIMED_OUT);
        }
        if (e instanceof ConnectException) {
            return unreachable(host, OutboundFailure.REFUSED);
        }
        return unreachable(host, OutboundFailure.FAILED);
    }

    private OutboundResponse exchange(OutboundRequest request) throws IOException {
        Exchange<?> exchange = current.get();
        URI uri = request.uri();
        for (int redirects = 0; ; redirects++) {
            // HttpClient can connect to a literal without asking the DNS hook, so judge literals here as well.
            if (OutboundAddressPolicy.isLiteral(uri.getHost())) {
                policy.addresses(uri.getHost());
            }
            exchange.check();
            OutboundResponse response = once(request, uri, exchange);
            if (!follows(request.method(), response.status())) {
                return response;
            }
            String location = response.header("location");
            if (location == null) {
                throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host(uri), OutboundFailure.NO_REDIRECT_TARGET, 0));
            }
            if (redirects >= profile.maxRedirects()) {
                throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host(uri), OutboundFailure.TOO_MANY_REDIRECTS, 0));
            }
            uri = next(uri, location);
        }
    }

    private boolean follows(String method, int status) {
        return profile.redirects() != Redirects.NONE && "GET".equals(method) && REDIRECT_STATUSES.contains(status);
    }

    private URI next(URI from, String location) {
        URI to;
        try {
            to = HttpUrls.parse(from.resolve(location).toString(), profile.urlRules());
        } catch (IllegalArgumentException _) {
            throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host(from), OutboundFailure.INVALID_REDIRECT, 0));
        }
        if (profile.redirects() == Redirects.SAME_ORIGIN && !sameOrigin(from, to)) {
            throw new Failed(new OutboundFailure(Kind.BLOCKED, host(from), OutboundFailure.OTHER_ORIGIN, 0));
        }
        return to;
    }

    private OutboundResponse once(OutboundRequest request, URI uri, Exchange<?> exchange) throws IOException {
        var message = new HttpUriRequestBase(request.method(), uri);
        message.setConfig(RequestConfig.custom()
                .setAuthenticationEnabled(false).setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(timeout(exchange.remaining()))
                .setResponseTimeout(timeout(exchange.remaining())).build());
        request.headers().forEach(message::setHeader);
        if (request.body() != null) {
            message.setEntity(new ByteArrayEntity(request.body(), request.contentType() == null
                    ? ContentType.APPLICATION_OCTET_STREAM : ContentType.parse(request.contentType())));
        }
        exchange.active.set(message);
        try {
            exchange.check();
            var response = CloseableHttpResponse.adapt(http.executeOpen(null, message, null));
            try {
                Map<String, String> headers = headersOf(response.getHeaders());
                String contentType = headers.get("content-type");
                int status = response.getCode();
                // Never drain a redirect being followed, nor an error body nobody asked for: it may never end.
                if (follows(request.method(), status) || (!successful(status) && !request.errorBody())) {
                    return new OutboundResponse(status, contentType, new byte[0], headers);
                }
                int cap = request.maxBytes() > 0 ? request.maxBytes() : profile.maxBytes();
                byte[] body = body(response.getEntity(), response.getHeaders("Content-Encoding"), host(uri), cap);
                exchange.check();
                return new OutboundResponse(status, contentType, body, headers);
            } finally {
                // Keep cancellation active through cleanup; never drain a body gracefully.
                message.cancel();
                response.close(CloseMode.IMMEDIATE);
            }
        } finally {
            message.cancel();
            exchange.active.compareAndSet(message, null);
        }
    }

    private static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    private static byte[] body(HttpEntity entity, Header[] encodings, String host, int cap) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        for (Header encoding : encodings) {
            if (!encoding.getValue().strip().equalsIgnoreCase("identity")) {
                throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host, OutboundFailure.COMPRESSED, 0));
            }
        }
        if (entity.getContentLength() > cap) {
            throw new Failed(new OutboundFailure(Kind.TOO_LARGE, host, OutboundFailure.TOO_LARGE, cap));
        }
        byte[] body = entity.getContent().readNBytes(cap + 1);
        if (body.length > cap) {
            throw new Failed(new OutboundFailure(Kind.TOO_LARGE, host, OutboundFailure.TOO_LARGE, cap));
        }
        return body;
    }

    private static Map<String, String> headersOf(Header[] all) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : all) {
            headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
        }
        return headers;
    }

    static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "an unknown host" : uri.getHost();
    }

    private static long earlier(long first, long second) {
        return first - second < 0 ? first : second;
    }

    private static Timeout timeout(long nanos) {
        return Timeout.ofMilliseconds(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    private static OutboundFailure unreachable(String host, String reason) {
        return new OutboundFailure(Kind.UNREACHABLE, host, reason, 0);
    }

    /** A failure found on the worker; the caller turns it into the source's exception. No stack, no cause. */
    private static final class Failed extends RuntimeException {
        private final transient OutboundFailure failure;

        Failed(OutboundFailure failure) {
            super(failure.reason(), null, false, false);
            this.failure = failure;
        }
    }

    private final class Exchange<T> {
        private final String host;
        private final long deadline;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpUriRequestBase> active = new AtomicReference<>();
        private final CompletableFuture<T> result = new CompletableFuture<>();
        // Set once by the worker before it runs; cancel() only reads it to interrupt that thread.
        @SuppressWarnings("java:S3077")
        private volatile Thread worker;

        Exchange(String host, long deadline) {
            this.host = host;
            this.deadline = deadline;
        }

        long remaining() {
            return Math.max(0, deadline - System.nanoTime());
        }

        boolean expired() {
            return cancelled.get() || remaining() == 0;
        }

        void check() {
            if (closed.get()) {
                throw new Failed(unreachable(host, OutboundFailure.CLOSED));
            }
            if (expired() || Thread.currentThread().isInterrupted()) {
                throw new Failed(unreachable(host, OutboundFailure.TIMED_OUT));
            }
        }

        void cancel() {
            cancelled.set(true);
            HttpUriRequestBase request = active.get();
            if (request != null) {
                request.cancel();
            }
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exchanges.forEach(exchange -> {
            exchange.cancel();
            exchange.result.completeExceptionally(new Failed(unreachable(exchange.host, OutboundFailure.CLOSED)));
        });
        workers.shutdownNow();
        // Do not wait for platform DNS that can ignore interruption; those bounded workers keep their slots.
        http.close(CloseMode.IMMEDIATE);
    }
}
