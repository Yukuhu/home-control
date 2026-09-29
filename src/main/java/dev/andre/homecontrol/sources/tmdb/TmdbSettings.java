package dev.andre.homecontrol.sources.tmdb;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/** Non-secret TMDB settings kept in sources.json, as this record's JSON. The credential lives in the secret store. */
public record TmdbSettings(TmdbCredential.Kind credentialKind, Instant connectedAt) {

    public static final String SOURCE_ID = "tmdb";
    public static final String CREDENTIAL_SECRET = "tmdb.credential";

    /** sources.json version 1's flat section. Empty without a known credential kind. */
    public static Optional<TmdbSettings> fromVersionOne(Map<String, String> map) {
        if (map == null) {
            return Optional.empty();
        }
        String kind = map.get("credentialKind");
        if (kind == null) {
            return Optional.empty();
        }
        TmdbCredential.Kind credentialKind;
        try {
            credentialKind = TmdbCredential.Kind.valueOf(kind);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        Instant connectedAt;
        try {
            connectedAt = Instant.parse(map.getOrDefault("connectedAt", ""));
        } catch (DateTimeParseException _) {
            connectedAt = Instant.EPOCH;
        }
        return Optional.of(new TmdbSettings(credentialKind, connectedAt));
    }
}
