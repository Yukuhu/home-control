package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundFailure;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import dev.andre.homecontrol.sources.http.Statuses;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.settings.SportsProperties;

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
    private static final String FOLD = "\r\n \t";

    private final GuardedHttpClient http;

    public CalendarFetcher(SportsProperties.Calendar properties, OutboundAddressPolicy policy) {
        http = new GuardedHttpClient(new GuardedHttpClient.Profile("the calendar", GuardedHttpClient.Redirects.CHECKED,
                properties.maxRedirects(), properties.maxBytes(), properties.connectTimeout(),
                properties.requestTimeout(), MAX_CONNECTIONS, CalendarLinks.RULES),
                policy, CalendarFetcher::failure);
    }

    /** People add calendars by hand, so a refused address says why, and which setting allows a calendar served here. */
    private static CalendarFetchException failure(OutboundFailure failure) {
        String message = failure.describe("the calendar");
        if (failure.kind() == Kind.BLOCKED
                && OutboundFailure.ADDRESS_NOT_ALLOWED.equals(failure.reason())) {
            message += ": that address belongs to this machine or its network link. If the calendar is served on this"
                    + " machine, set HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK=true.";
        }
        return new CalendarFetchException(failure.kind(), message);
    }

    public String fetch(URI url) {
        OutboundResponse response = http.send(OutboundRequest.get(url)
                .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                .header("User-Agent", "HomeControl"));
        int status = response.status();
        if (status == 200) {
            Charset charset = charsetOf(response.contentType());
            // RFC 5545 folds lines at octets, so a fold can split a character: unfold before decoding, in a charset
            // whose line breaks are single ASCII bytes.
            return new String(asciiLineBreaks(charset) ? IcsParser.unfold(response.body()) : response.body(), charset);
        }
        String host = response.uri().getHost();
        Kind kind = Statuses.kindOf(status);
        throw new CalendarFetchException(kind, switch (kind) {
            case UNAUTHORIZED -> host + " refused access to the calendar";
            case NOT_FOUND -> host + " has no calendar at that link";
            default -> host + " answered HTTP " + status;
        });
    }

    /** Whether line breaks, spaces and tabs are the single ASCII bytes the byte-level unfold looks for (not UTF-16). */
    private static boolean asciiLineBreaks(Charset charset) {
        return FOLD.equals(new String(FOLD.getBytes(StandardCharsets.US_ASCII), charset));
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
