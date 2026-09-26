package dev.andre.homecontrol.discovery.ssdp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The only way device-announced XML (descriptions, SCPDs) is fetched: from an address the device
 * announced itself from, over plain HTTP, with a hard size cap. Shared by {@link SsdpDiscovery}
 * and every adapter that re-reads a description on its own (UPnP renderers, Sonos).
 */
public final class DeviceFetch {

    /** A device description or SCPD is small XML; anything past this is refused rather than read into memory. */
    public static final long MAX_DESCRIPTION_BYTES = 64 * 1024;
    public static final String USER_AGENT = "Linux/1 UPnP/1.1 HomeControl/1";
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private DeviceFetch() {
    }

    /**
     * Only ever fetch a description from the address it was announced from (spec §1.1's LOCATION
     * is carried in an unauthenticated UDP payload, so trusting it as-is would let one forged
     * datagram make this appliance issue an HTTP GET to any URL of the sender's choosing — a
     * server-side request forgery into the LAN or, via a public multicast relay, further still).
     * {@code https} and other schemes are refused outright (no adapter's UPnP services use them);
     * the host must be an IP literal so nothing here ever triggers a DNS lookup driven by an
     * unauthenticated datagram, and that literal must equal the datagram's sender; the port must
     * be explicit, matching every real UPnP LOCATION.
     */
    public static boolean isSafeToFetch(URI location, InetAddress sender) {
        if (location == null || sender == null || !"http".equalsIgnoreCase(location.getScheme())) {
            return false;
        }
        String host = location.getHost();
        if (host == null || !isIpLiteral(host) || location.getPort() <= 0) {
            return false;
        }
        try {
            return InetAddress.getByName(host).equals(sender);
        } catch (UnknownHostException _) {
            // Unreachable: an address that passed isIpLiteral never performs a lookup and never fails.
            return false;
        }
    }

    /** {@link #isSafeToFetch(URI, InetAddress)} for an address kept as text (an {@link SsdpService#address()}). */
    public static boolean isSafeToFetch(URI location, String announcedAddress) {
        if (announcedAddress == null || !isIpLiteral(announcedAddress)) {
            return false;
        }
        try {
            return isSafeToFetch(location, InetAddress.getByName(announcedAddress));
        } catch (UnknownHostException _) {
            return false;
        }
    }

    /** True only for a textual IPv4/IPv6 address — never a name that would need a DNS lookup to resolve. */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (IPV4_LITERAL.matcher(host).matches()) {
            return true;
        }
        // An IPv6 literal contains only hex digits, ':', '.' (an embedded IPv4 tail) and '%'
        // (a zone id) — never a letter outside a-f, so this can never accidentally match a DNS hostname.
        return host.indexOf(':') >= 0 && host.chars().allMatch(c ->
                Character.isDigit(c) || c == ':' || c == '.' || c == '%' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    /**
     * GETs {@code url} and returns its body, or throws: any status but 200 (redirects included —
     * clients built for devices never follow them), a declared or actual body over {@code maxBytes},
     * or no complete answer within {@code timeout}. The caller has already decided {@code url} is safe.
     */
    public static byte[] get(HttpClient http, URI url, Duration timeout, long maxBytes)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send(http,
                HttpRequest.newBuilder(url).timeout(timeout).header("User-Agent", USER_AGENT).GET().build(),
                maxBytes, timeout);
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Sends {@code request} with a hard cap on the answer: a body declared or actually larger than
     * {@code maxBytes} fails with an {@link IOException} without being buffered, and the whole
     * exchange — headers and body — must finish within {@code deadline}, so a device that trickles
     * bytes cannot hold the caller either ({@link HttpTimeoutException} otherwise).
     */
    public static HttpResponse<byte[]> send(HttpClient http, HttpRequest request, long maxBytes, Duration deadline)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(request, info -> new BoundedBody(
                maxBytes, contentLength(info.headers())));
        try {
            return exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            exchange.cancel(true);
            throw new HttpTimeoutException("no complete answer within " + deadline.toMillis() + " ms");
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()), e.getCause());
        }
    }

    public static OptionalLong contentLength(HttpHeaders headers) {
        try {
            return headers.firstValueAsLong("Content-Length");
        } catch (NumberFormatException _) {
            return OptionalLong.empty();
        }
    }

    /** Collects a response body up to a cap; past it, cancels the stream and fails. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long maxBytes;
        private final OptionalLong declared;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long total;

        BoundedBody(long maxBytes, OptionalLong declared) {
            this.maxBytes = maxBytes;
            this.declared = declared;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declared.isPresent() && declared.getAsLong() > maxBytes) {
                subscription.cancel();
                result.completeExceptionally(new IOException(
                        "declares " + declared.getAsLong() + " bytes, over the " + maxBytes + "-byte cap"));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                total += item.remaining();
                if (total > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("exceeds the " + maxBytes + "-byte cap"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }
}
