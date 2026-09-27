package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.VettedHttpClients;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException.Kind;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches a calendar over HTTP(S), following only validated redirects. Never trusts the caller's URL:
 * {@link CalendarUrlPolicy} vets every hop, and the connection goes to exactly the addresses it vetted, so a
 * host cannot pass the check with one address and be connected to at another.
 * One deadline per request covers the headers and the whole body.
 */
public class CalendarFetcher implements AutoCloseable {

    /** The schedule fetches calendars one after another; a few more connections cover the setup page's checks. */
    private static final int MAX_CONNECTIONS = 4;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final SportsProperties.Calendar properties;
    private final CalendarUrlPolicy policy;
    private final CloseableHttpClient http;

    public CalendarFetcher(SportsProperties.Calendar properties, CalendarUrlPolicy policy) {
        this.properties = properties;
        this.policy = policy;
        this.http = VettedHttpClients.create(policy::addresses, MAX_CONNECTIONS,
                Duration.ofSeconds(properties.connectTimeoutSeconds()));
    }

    public String fetch(URI url) {
        URI current = url;
        for (int hop = 0; ; hop++) {
            Hop answer = request(current, hop);
            if (answer.redirect() == null) {
                return answer.text();
            }
            current = answer.redirect();
        }
    }

    /** One request's outcome: the calendar text, or the validated link it redirected to. */
    private record Hop(String text, URI redirect) {
    }

    private Hop request(URI target, int hop) {
        String host = target.getHost();
        if (Thread.currentThread().isInterrupted()) {
            // Shutting down: keep the interrupt for the caller and open no new connection.
            throw new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host);
        }
        // A clear answer before any connection; the connection itself is vetted again by the same policy.
        policy.checkAddress(target);
        Timeout timeout = Timeout.ofSeconds(properties.requestTimeoutSeconds());
        HttpGet request = new HttpGet(target);
        request.setConfig(RequestConfig.custom()
                .setAuthenticationEnabled(false)
                .setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .build());
        request.setHeader("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5");
        request.setHeader("User-Agent", "HomeControl");
        // The response timeout bounds each read, not the whole body: a server that trickles its calendar would
        // hold the refresh. Cancelling at the deadline closes the connection mid-read.
        CompletableFuture.delayedExecutor(properties.requestTimeoutSeconds(), TimeUnit.SECONDS).execute(request::cancel);
        CloseableHttpResponse response = null;
        try {
            response = CloseableHttpResponse.adapt(http.executeOpen(null, request, null));
            return read(response, target, hop);
        } catch (IOException e) {
            throw unreachable(host, e);
        } finally {
            // Never drain a redirect or error body: drop the connection at once.
            request.cancel();
            if (response != null) {
                response.close(CloseMode.IMMEDIATE);
            }
        }
    }

    /** Reads the calendar from a 200, or where a redirect points; every other answer ends the fetch. */
    private Hop read(CloseableHttpResponse response, URI current, int hop) throws IOException {
        String host = current.getHost();
        int status = response.getCode();
        if (REDIRECTS.contains(status)) {
            return new Hop(null, redirectTarget(response, current, hop));
        }
        if (status == 401 || status == 403) {
            throw new CalendarFetchException(Kind.UNAUTHORIZED, host + " refused access to the calendar");
        }
        if (status == 404 || status == 410) {
            throw new CalendarFetchException(Kind.NOT_FOUND, host + " has no calendar at that link");
        }
        if (status != 200) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE, host + " answered HTTP " + status);
        }
        HttpEntity entity = response.getEntity();
        byte[] bytes = entity == null ? new byte[0] : entity.getContent().readNBytes(properties.maxBytes() + 1);
        if (bytes.length > properties.maxBytes()) {
            throw new CalendarFetchException(Kind.TOO_LARGE,
                    "The calendar is larger than " + (properties.maxBytes() / 1_048_576) + " MB");
        }
        return new Hop(new String(bytes, charsetOf(response)), null);
    }

    private URI redirectTarget(CloseableHttpResponse response, URI current, int hop) {
        String host = current.getHost();
        Header location = response.getFirstHeader("Location");
        if (location == null) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE, host + " answered HTTP " + response.getCode());
        }
        if (hop >= properties.maxRedirects()) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE, "The calendar link redirected too many times");
        }
        try {
            return policy.parse(current.resolve(location.getValue()).toString());
        } catch (IllegalArgumentException _) {
            throw new CalendarFetchException(Kind.BAD_RESPONSE,
                    host + " redirected to a link Home Control does not follow");
        }
    }

    private static Charset charsetOf(CloseableHttpResponse response) {
        Header contentType = response.getFirstHeader("Content-Type");
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType.getValue());
            if (m.find()) {
                try {
                    if (Charset.isSupported(m.group(1).strip())) {
                        return Charset.forName(m.group(1).strip());
                    }
                } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static CalendarFetchException unreachable(String host, Exception cause) {
        String message = cause.getMessage();
        boolean leaksUrl = message != null && message.toLowerCase(Locale.ROOT).contains(host.toLowerCase(Locale.ROOT))
                && message.contains("/");
        return leaksUrl
                ? new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host)
                : new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host, cause);
    }

    @Override
    public void close() {
        http.close(CloseMode.IMMEDIATE);
    }
}
