package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.sports.SportsProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException.Kind;

/**
 * Fetches a calendar over HTTP(S), following only validated redirects. Never trusts the caller's URL:
 * {@link CalendarUrlPolicy#checkAddress(URI)} runs before every connection attempt, including each hop.
 */
public class CalendarFetcher {

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final SportsProperties.Calendar properties;
    private final CalendarUrlPolicy policy;
    private final HttpClient client;

    public CalendarFetcher(SportsProperties.Calendar properties, CalendarUrlPolicy policy) {
        this(properties, policy, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build());
    }

    public CalendarFetcher(SportsProperties.Calendar properties, CalendarUrlPolicy policy, HttpClient client) {
        this.properties = properties;
        this.policy = policy;
        this.client = client;
    }

    public String fetch(URI url) {
        URI current = url;
        for (int hop = 0; ; hop++) {
            policy.checkAddress(current);
            String host = current.getHost();
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                    .header("User-Agent", "HomeControl")
                    .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw unreachable(host, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unreachable(host, e);
            }
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (REDIRECTS.contains(status)) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) {
                        throw new CalendarFetchException(Kind.BAD_RESPONSE, host + " answered HTTP " + status);
                    }
                    if (hop >= properties.maxRedirects()) {
                        throw new CalendarFetchException(Kind.BAD_RESPONSE, "The calendar link redirected too many times");
                    }
                    try {
                        current = policy.parse(current.resolve(location).toString());
                    } catch (IllegalArgumentException _) {
                        throw new CalendarFetchException(Kind.BAD_RESPONSE,
                                host + " redirected to a link Home Control does not follow");
                    }
                    continue;
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
                byte[] bytes = body.readNBytes(properties.maxBytes() + 1);
                if (bytes.length > properties.maxBytes()) {
                    throw new CalendarFetchException(Kind.TOO_LARGE,
                            "The calendar is larger than " + (properties.maxBytes() / 1_048_576) + " MB");
                }
                return new String(bytes, charsetOf(response));
            } catch (IOException e) {
                throw unreachable(host, e);
            }
        }
    }

    private static Charset charsetOf(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
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
}
