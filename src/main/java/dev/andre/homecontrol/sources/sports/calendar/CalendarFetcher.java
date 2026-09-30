package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import dev.andre.homecontrol.sources.http.Statuses;
import dev.andre.homecontrol.sources.sports.SportsProperties;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches calendar links through the guarded client: every hop's address checked and pinned, redirects to any
 * origin re-checked, one deadline for the whole chain, and the body capped.
 */
public class CalendarFetcher implements AutoCloseable {

    /** The schedule fetches calendars one after another; a few more slots cover the setup page's checks. */
    private static final int MAX_CONNECTIONS = 4;
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final GuardedHttpClient http;

    public CalendarFetcher(SportsProperties.Calendar properties, OutboundAddressPolicy policy) {
        http = new GuardedHttpClient(new GuardedHttpClient.Profile("the calendar", GuardedHttpClient.Redirects.CHECKED,
                properties.maxRedirects(), properties.maxBytes(), properties.connectTimeout(),
                properties.requestTimeout(), MAX_CONNECTIONS, CalendarLinks.RULES),
                policy, failure -> new CalendarFetchException(failure.kind(), failure.describe("the calendar")));
    }

    public String fetch(URI url) {
        OutboundResponse response = http.send(OutboundRequest.get(url)
                .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                .header("User-Agent", "HomeControl"));
        int status = response.status();
        if (status == 200) {
            return new String(response.body(), charsetOf(response.contentType()));
        }
        String host = response.uri().getHost();
        Kind kind = Statuses.kindOf(status);
        throw new CalendarFetchException(kind, switch (kind) {
            case UNAUTHORIZED -> host + " refused access to the calendar";
            case NOT_FOUND -> host + " has no calendar at that link";
            default -> host + " answered HTTP " + status;
        });
    }

    private static Charset charsetOf(String contentType) {
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

    @Override
    public void close() {
        http.close();
    }
}
