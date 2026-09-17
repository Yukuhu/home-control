package dev.andre.homecontrol.sources.tmdb;

import java.util.regex.Pattern;

/** A TMDB v4 API Read Access Token (sent as a bearer header) or a v3 API key (sent as {@code api_key}). */
public record TmdbCredential(Kind kind, String value) {

    public enum Kind { BEARER, API_KEY }

    private static final Pattern API_KEY = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern JWT = Pattern.compile("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$");
    private static final int MAX_LENGTH = 2048;

    public static TmdbCredential parse(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).strip();
        }
        if (API_KEY.matcher(value).matches()) {
            return new TmdbCredential(Kind.API_KEY, value);
        }
        if (value.length() <= MAX_LENGTH && JWT.matcher(value).matches()) {
            return new TmdbCredential(Kind.BEARER, value);
        }
        throw new IllegalArgumentException("Paste the API Read Access Token or the API key from your TMDB account settings");
    }

    public String describe() {
        return kind == Kind.BEARER ? "read access token" : "API key";
    }

    @Override
    public String toString() {
        return "TmdbCredential[" + kind + ", redacted]";
    }
}
