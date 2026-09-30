package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.HttpUrls;

import java.net.URI;

/** The calendar links people add: http, https or webcal, a query and fragment allowed, at most 2,048 characters. */
public final class CalendarLinks {

    static final HttpUrls.Rules RULES = new HttpUrls.Rules(true, true, true, true, 2_048);

    private CalendarLinks() {
    }

    /** The link to fetch; throws {@link IllegalArgumentException} with a sentence for the setup page. */
    public static URI parse(String raw) {
        try {
            return HttpUrls.parse(raw == null ? null : raw.strip(), RULES);
        } catch (HttpUrls.InvalidUrlException e) {
            throw new IllegalArgumentException(switch (e.problem()) {
                case MISSING -> "Enter a calendar link";
                case TOO_LONG -> "That calendar link is too long";
                case SCHEME -> "Use an http, https or webcal link";
                case USER_INFO -> "Links with a user name or password are not supported; use the calendar's secret link instead";
                default -> "That is not a valid link";
            }, e);
        }
    }
}
