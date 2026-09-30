package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.HttpUrls;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** A workflow's GETs, same-origin redirects only, through the guarded client: one deadline, one slot, one body cap. */
public final class WorkflowHttpClient implements AutoCloseable {

    static final HttpUrls.Rules CALL_URLS = new HttpUrls.Rules(true, false, true, false, 8_192);
    private static final Set<String> DENIED_HEADERS = Set.of("host", "cookie", "connection", "content-length",
            "transfer-encoding", "te", "trailer", "upgrade", "keep-alive", "expect", "accept-encoding", "proxy");

    private final GuardedHttpClient http;

    public WorkflowHttpClient(WorkflowProperties properties, OutboundAddressPolicy policy) {
        http = new GuardedHttpClient(new GuardedHttpClient.Profile("the workflow source",
                GuardedHttpClient.Redirects.SAME_ORIGIN, properties.maxRedirects(), properties.maxBytes(),
                properties.connectTimeout(), properties.requestTimeout(), properties.maxConcurrentFetches(), CALL_URLS),
                policy, failure -> new WorkflowException(Stage.FETCH, failure.reason()));
    }

    /** One GET with its URL and header values already expanded. Printing it shows neither. */
    public record Request(String url, List<WorkflowDraft.Header> headers) {
        public Request {
            headers = headers == null ? null : Collections.unmodifiableList(new java.util.ArrayList<>(headers));
        }

        @Override public String toString() { return "Request"; }
    }

    /** Fails at once with "busy" when every fetch slot is taken. */
    public byte[] fetch(Request request) {
        return fetch(outbound(request).failingFastWhenBusy());
    }

    /** Waits for a fetch slot until {@code deadline}; the fetch ends at the deadline or the request timeout. */
    public byte[] fetch(Request request, long deadline) {
        return fetch(outbound(request).endingBy(deadline));
    }

    public void checkMedia(URI uri) {
        checkMedia(OutboundRequest.get(parse(uri, Stage.BUILD)).failingFastWhenBusy());
    }

    public void checkMedia(URI uri, long deadline) {
        checkMedia(OutboundRequest.get(parse(uri, Stage.BUILD)).endingBy(deadline));
    }

    private void checkMedia(OutboundRequest request) {
        try {
            http.vet(request);
        } catch (WorkflowException failure) {
            throw new WorkflowException(Stage.BUILD, failure.detail());
        }
    }

    private byte[] fetch(OutboundRequest request) {
        OutboundResponse response = http.send(request);
        if (response.status() != 200) {
            throw new WorkflowException(Stage.FETCH, "server returned HTTP " + response.status());
        }
        return response.body();
    }

    private static OutboundRequest outbound(Request fetch) {
        if (fetch == null || fetch.headers() == null || fetch.headers().size() > 16) {
            throw new WorkflowException(Stage.FETCH, "invalid request settings");
        }
        OutboundRequest request = OutboundRequest.get(parse(fetch.url(), Stage.FETCH)).header("Accept", "application/json");
        for (var header : fetch.headers()) {
            validateHeader(header);
            request = request.header(header.name(), header.value());
        }
        return request;
    }

    private static URI parse(URI uri, Stage stage) {
        return parse(uri == null ? null : uri.toString(), stage);
    }

    /** A call URL by the workflow rules: http(s), no fragment, at most 8,192 characters. */
    static URI parse(String value) {
        return parse(value, Stage.FETCH);
    }

    private static URI parse(String value, Stage stage) {
        try {
            return HttpUrls.parse(value, CALL_URLS);
        } catch (HttpUrls.InvalidUrlException _) {
            throw new WorkflowException(stage, "invalid HTTP URL");
        }
    }

    private static void validateHeader(WorkflowDraft.Header header) {
        if (header == null || header.name() == null || !header.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new WorkflowException(Stage.FETCH, "invalid request header");
        }
        String name = header.name().toLowerCase(Locale.ROOT);
        if (DENIED_HEADERS.contains(name) || name.startsWith("proxy-") || header.value() == null
                || header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
            throw new WorkflowException(Stage.FETCH, "invalid request header");
        }
    }

    @Override public void close() {
        http.close();
    }
}
