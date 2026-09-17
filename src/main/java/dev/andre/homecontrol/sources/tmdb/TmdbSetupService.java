package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Connects, checks and disconnects TMDB; the only writer of its settings and credential. */
public class TmdbSetupService {

    public record ConnectRequest(String credential, String loginPassword, String loginPasswordConfirmation) {
        @Override
        public String toString() {
            return "ConnectRequest[redacted]";
        }
    }

    private final TmdbClient client;
    private final JsonFileSourceSettings sources;
    private final SecretStore secrets;
    private final LoginService login;
    private final Clock clock;

    public TmdbSetupService(TmdbClient client, JsonFileSourceSettings sources, SecretStore secrets,
                            LoginService login, Clock clock) {
        this.client = client;
        this.sources = sources;
        this.secrets = secrets;
        this.login = login;
        this.clock = clock;
    }

    public Optional<TmdbSettings> settings() {
        return TmdbSettings.from(sources.get(TmdbSettings.SOURCE_ID));
    }

    public Optional<TmdbCredential> credential() {
        if (settings().isEmpty()) {
            return Optional.empty();
        }
        return secrets.secret(TmdbSettings.CREDENTIAL_SECRET).flatMap(value -> {
            try {
                return Optional.of(TmdbCredential.parse(value));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    public TmdbSettings connect(ConnectRequest request, HttpServletRequest http) {
        TmdbCredential credential;
        try {
            credential = TmdbCredential.parse(request.credential());
        } catch (IllegalArgumentException e) {
            throw new TmdbException(TmdbException.Kind.INVALID_INPUT, e.getMessage());
        }
        validate(credential);
        login.storeSecrets(Map.of(TmdbSettings.CREDENTIAL_SECRET, credential.value()),
                request.loginPassword(), request.loginPasswordConfirmation(), http);
        TmdbSettings settings = new TmdbSettings(credential.kind(), clock.instant());
        sources.put(TmdbSettings.SOURCE_ID, settings.toMap());
        return settings;
    }

    public String check() {
        TmdbCredential credential = credential().orElseThrow(() ->
                new TmdbException(TmdbException.Kind.INVALID_INPUT, "TMDB is not connected"));
        validate(credential);
        return "TMDB accepted the " + credential.describe();
    }

    public void disconnect() {
        login.removeSecrets(List.of(TmdbSettings.CREDENTIAL_SECRET));
        sources.remove(TmdbSettings.SOURCE_ID);
    }

    private void validate(TmdbCredential credential) {
        var response = client.get(credential, "/authentication", Map.of());
        if (!response.path("success").asBoolean(false)) {
            throw new TmdbException(TmdbException.Kind.UNAUTHORIZED, "TMDB rejected the API key or read access token");
        }
    }
}
