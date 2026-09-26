package dev.andre.homecontrol.sources.workflows;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
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

import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** One total deadline and one worker-owned permit cover DNS, all redirects, and the entire response body. */
public final class WorkflowHttpClient implements AutoCloseable {
    private static final String CLIENT_CLOSED = "client is closed";
    private static final Set<String> DENIED_HEADERS = Set.of("host", "cookie", "connection", "content-length",
            "transfer-encoding", "te", "trailer", "upgrade", "keep-alive", "expect", "accept-encoding", "proxy");
    private final WorkflowProperties properties;
    private final WorkflowUrlPolicy policy;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CloseableHttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Operation<?>> operations = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Operation<?>> current = new ThreadLocal<>();

    public WorkflowHttpClient(WorkflowProperties properties, WorkflowUrlPolicy policy) {
        this.properties = properties;
        this.policy = policy;
        permits = new Semaphore(properties.maxConcurrentFetches());
        DnsResolver dns = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                Operation<?> operation = current.get();
                operation.check();
                InetAddress[] addresses = policy.addresses(host);
                // A platform resolver can ignore interruption. Never connect after it returns late.
                operation.check();
                return addresses;
            }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setMaxConnTotal(properties.maxConcurrentFetches())
                .setMaxConnPerRoute(properties.maxConcurrentFetches())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(properties.connectTimeout().toNanos())).build())
                .build();
        http = HttpClients.custom().setConnectionManager(manager)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .disableAuthCaching().disableContentCompression()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }

    public byte[] fetch(WorkflowDraft.Fetch fetch) {
        return bounded(Stage.FETCH, () -> fetchBody(fetch));
    }

    public void checkMedia(URI uri) {
        bounded(Stage.BUILD, () -> {
            URI checked = policy.parse(uri == null ? null : uri.toString());
            current.get().check();
            policy.addresses(checked.getHost());
            current.get().check();
            return null;
        });
    }

    private byte[] fetchBody(WorkflowDraft.Fetch fetch) throws Exception {
        if (fetch == null || fetch.headers() == null || fetch.headers().size() > 16) {
            throw failure(Stage.FETCH, "invalid request settings");
        }
        URI uri = policy.parse(fetch.url());
        Operation<?> operation = current.get();
        for (int redirects = 0; ; redirects++) {
            operation.check();
            validateLiteralAddress(uri, operation);
            FetchResponse response = fetchOnce(uri, fetch.headers(), operation);
            if (response.redirectLocation() == null) return response.body();
            if (redirects >= properties.maxRedirects()) throw failure(Stage.FETCH, "too many redirects");
            uri = redirect(uri, response.redirectLocation());
        }
    }

    private void validateLiteralAddress(URI uri, Operation<?> operation) throws UnknownHostException {
        // HttpClient can bypass DnsResolver for literals, so check those here as well.
        if (WorkflowUrlPolicy.literal(uri.getHost()) != null) policy.addresses(uri.getHost());
        operation.check();
    }

    private URI redirect(URI uri, String location) {
        URI next = policy.parse(uri.resolve(location).toString());
        if (!policy.sameOrigin(uri, next)) {
            throw failure(Stage.FETCH, "redirect changes origin; configure the final source URL");
        }
        return next;
    }

    private FetchResponse fetchOnce(URI uri, List<WorkflowDraft.Header> headers, Operation<?> operation)
            throws IOException {
        var request = new HttpGet(uri);
        request.setConfig(RequestConfig.custom()
                .setAuthenticationEnabled(false).setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(timeout(operation.remaining()))
                .setResponseTimeout(timeout(operation.remaining())).build());
        request.setHeader("Accept", "application/json");
        for (var header : headers) {
            validateHeader(header);
            request.setHeader(header.name(), header.value());
        }
        operation.active.set(request);
        try {
            operation.check();
            var response = CloseableHttpResponse.adapt(http.executeOpen(null, request, null));
            try {
                int status = response.getCode();
                if (isRedirect(status)) return new FetchResponse(null, redirectLocation(response));
                if (status != 200) throw failure(Stage.FETCH, "server returned HTTP " + status);
                validateContentEncoding(response);
                var entity = response.getEntity();
                if (entity == null) return new FetchResponse(new byte[0], null);
                byte[] body = entity.getContent().readNBytes(properties.maxBytes() + 1);
                operation.check();
                if (body.length > properties.maxBytes()) throw failure(Stage.FETCH, "response is too large");
                return new FetchResponse(body, null);
            } finally {
                // Keep cancellation active through cleanup; never gracefully drain a redirect/error body.
                request.cancel();
                response.close(CloseMode.IMMEDIATE);
            }
        } finally {
            request.cancel();
            operation.active.compareAndSet(request, null);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String redirectLocation(CloseableHttpResponse response) {
        var location = response.getFirstHeader("Location");
        if (location == null) throw failure(Stage.FETCH, "redirect has no destination");
        return location.getValue();
    }

    private static void validateContentEncoding(CloseableHttpResponse response) {
        for (var encoding : response.getHeaders("Content-Encoding")) {
            if (!encoding.getValue().equalsIgnoreCase("identity")) {
                throw failure(Stage.FETCH, "response compression is not supported");
            }
        }
    }

    private static final class FetchResponse {
        private final byte[] body;
        private final String redirectLocation;

        private FetchResponse(byte[] body, String redirectLocation) {
            this.body = body;
            this.redirectLocation = redirectLocation;
        }

        private byte[] body() {
            return body;
        }

        private String redirectLocation() {
            return redirectLocation;
        }
    }

    private static void validateHeader(WorkflowDraft.Header header) {
        if (header == null || header.name() == null || !header.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw failure(Stage.FETCH, "invalid request header");
        }
        String name = header.name().toLowerCase(Locale.ROOT);
        if (DENIED_HEADERS.contains(name) || name.startsWith("proxy-") || header.value() == null
                || header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
            throw failure(Stage.FETCH, "invalid request header");
        }
    }

    private <T> T bounded(Stage stage, Callable<T> work) {
        if (closed.get()) throw failure(stage, CLIENT_CLOSED);
        if (!permits.tryAcquire()) throw failure(stage, "busy; try again later");
        var operation = new Operation<T>(stage);
        operations.add(operation);
        try {
            executor.execute(() -> {
                T value = null;
                Throwable error = null;
                operation.worker = Thread.currentThread();
                current.set(operation);
                try {
                    operation.check();
                    value = work.call();
                    operation.check();
                } catch (Throwable e) {
                    error = e;
                } finally {
                    current.remove();
                    operations.remove(operation);
                    // Only the worker releases admission, even when its caller has already timed out.
                    permits.release();
                }
                if (error == null) operation.result.complete(value);
                else operation.result.completeExceptionally(error);
            });
        } catch (RejectedExecutionException _) {
            operations.remove(operation);
            permits.release();
            throw failure(stage, CLIENT_CLOSED);
        }
        try {
            return operation.result.get(operation.remaining(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            operation.cancel();
            Thread.currentThread().interrupt();
            throw failure(stage, "request interrupted");
        } catch (TimeoutException _) {
            operation.cancel();
            throw failure(stage, "request timed out");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WorkflowException safe && safe.stage() == stage) throw safe;
            throw failure(stage, "request failed");
        }
    }

    private static Timeout timeout(long nanos) { return Timeout.ofMilliseconds(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos))); }
    private static WorkflowException failure(Stage stage, String detail) { return new WorkflowException(stage, detail); }

    private final class Operation<T> {
        private final Stage stage;
        private final long deadline = System.nanoTime() + properties.requestTimeout().toNanos();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpGet> active = new AtomicReference<>();
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private volatile Thread worker;

        Operation(Stage stage) { this.stage = stage; }
        long remaining() { return Math.max(0, deadline - System.nanoTime()); }
        void check() {
            if (closed.get() || cancelled.get() || Thread.currentThread().isInterrupted() || remaining() == 0) {
                throw failure(stage, "request cancelled or timed out");
            }
        }
        void cancel() {
            cancelled.set(true);
            HttpGet request = active.get();
            if (request != null) request.cancel();
            Thread thread = worker;
            if (thread != null) thread.interrupt();
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        operations.forEach(operation -> {
            operation.cancel();
            operation.result.completeExceptionally(failure(operation.stage, CLIENT_CLOSED));
        });
        executor.shutdownNow();
        // Do not wait for platform DNS that can ignore interruption; those bounded workers retain their permits.
        http.close(CloseMode.IMMEDIATE);
    }
}
