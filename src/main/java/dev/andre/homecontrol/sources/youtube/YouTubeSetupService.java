package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Connects, checks and disconnects the Google account; the only writer of YouTube's settings and secrets. */
public class YouTubeSetupService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeSetupService.class);

    private static final Pattern CLIENT_ID_PATTERN =
            Pattern.compile("^[0-9]{6,20}-[a-z0-9]{8,64}\\.apps\\.googleusercontent\\.com$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_CLIENT_SECRET_LENGTH = 200;
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final int MAX_SELECTED_PLAYLISTS = 20;

    public record ConnectRequest(String clientId, String clientSecret, String loginPassword,
                                 String loginPasswordConfirmation) {
        @Override
        public String toString() {
            return "ConnectRequest[clientId=" + clientId + "]";
        }
    }

    private final SecretStore secrets;
    private final LoginService login;
    private final JsonFileSourceSettings sourceSettings;
    private final GoogleOAuthClient oauth;
    private final GoogleTokens tokens;
    private final YouTubeAuthorizationService authorization;
    private final ObjectProvider<YouTubeAccount> account;
    private final QuotaLedger ledger;
    private final ObjectProvider<YouTubeContentSource> source;
    private final ObjectProvider<YouTubePlaylists> playlists;

    public YouTubeSetupService(SecretStore secrets, LoginService login, JsonFileSourceSettings sourceSettings,
                               GoogleOAuthClient oauth, GoogleTokens tokens, YouTubeAuthorizationService authorization,
                               ObjectProvider<YouTubeAccount> account, QuotaLedger ledger,
                               ObjectProvider<YouTubeContentSource> source, ObjectProvider<YouTubePlaylists> playlists) {
        this.secrets = secrets;
        this.login = login;
        this.sourceSettings = sourceSettings;
        this.oauth = oauth;
        this.tokens = tokens;
        this.authorization = authorization;
        this.account = account;
        this.ledger = ledger;
        this.source = source;
        this.playlists = playlists;
    }

    public YouTubeSettings settings() {
        return YouTubeSettings.from(sourceSettings.get(YouTubeSettings.SOURCE_ID));
    }

    public void save(YouTubeSettings settings) {
        sourceSettings.put(YouTubeSettings.SOURCE_ID, settings.toMap());
    }

    public boolean hasClient() {
        return tokens.hasClient();
    }

    public boolean connected() {
        return tokens.hasClient() && tokens.hasRefreshToken();
    }

    public boolean revoked() {
        return tokens.revoked();
    }

    public YouTubeAuthorizationService.Status connect(ConnectRequest request, HttpServletRequest http) {
        String clientId = request.clientId() == null ? "" : request.clientId().strip();
        if (!CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT,
                    "That does not look like an OAuth client ID (it ends in .apps.googleusercontent.com)");
        }
        String clientSecret = request.clientSecret() == null ? "" : request.clientSecret().strip();
        boolean hadSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).isPresent();
        if (clientSecret.isBlank() && !hadSecret) {
            throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Enter the client secret");
        }
        if (clientSecret.length() > MAX_CLIENT_SECRET_LENGTH) {
            throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "That client secret is too long");
        }
        boolean clientIdChanged = !clientId.equals(secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null));
        Map<String, String> values = new LinkedHashMap<>();
        values.put(YouTubeSettings.CLIENT_ID, clientId);
        if (!clientSecret.isBlank()) {
            values.put(YouTubeSettings.CLIENT_SECRET, clientSecret);
        }
        login.storeSecrets(values, request.loginPassword(), request.loginPasswordConfirmation(), http);
        if (clientIdChanged && secrets.secret(YouTubeSettings.REFRESH_TOKEN).isPresent()
                && secrets.names().size() > 1) {
            login.removeSecrets(List.of(YouTubeSettings.REFRESH_TOKEN));
            tokens.reset();
        }
        return authorization.start();
    }

    public YouTubeAuthorizationService.Status authorize() {
        return authorization.start();
    }

    public YouTubeAuthorizationService.Status authorizationStatus() {
        return authorization.status();
    }

    public void cancel() {
        authorization.cancel();
    }

    public String check() {
        tokens.invalidate();
        YouTubeAccount youTubeAccount = account.getIfAvailable();
        if (youTubeAccount == null) {
            tokens.accessToken();
            return "Google accepted the saved authorization";
        }
        String title = youTubeAccount.refreshChannel();
        QuotaLedger.Usage usage = ledger.usage();
        return "Connected as " + title + ". " + usage.units() + " of " + usage.dailyUnits() + " quota units used today.";
    }

    public List<YouTubePlaylists.PlaylistSummary> loadPlaylists() {
        YouTubePlaylists p = playlists.getIfAvailable();
        return p == null ? List.of() : p.mine();
    }

    public void choosePlaylists(List<String> playlistIds) {
        List<String> ids = playlistIds == null ? List.of() : playlistIds;
        if (ids.size() > MAX_SELECTED_PLAYLISTS) {
            throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Choose at most " + MAX_SELECTED_PLAYLISTS + " playlists");
        }
        YouTubePlaylists p = playlists.getIfAvailable();
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String id : ids) {
            if (id == null || !PLAYLIST_ID_PATTERN.matcher(id).matches()) {
                throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Load your playlists again, then choose");
            }
            Optional<YouTubePlaylists.PlaylistSummary> loaded = p == null ? Optional.empty() : p.loaded(id);
            if (loaded.isEmpty()) {
                throw new YouTubeException(YouTubeException.Kind.INVALID_INPUT, "Load your playlists again, then choose");
            }
            chosen.put(id, loaded.get().title());
        }
        save(settings().withPlaylists(chosen));
    }

    public void setWatchLater(boolean enabled) {
        save(settings().withWatchLater(enabled));
    }

    public void disconnect() {
        authorization.cancel();
        secrets.secret(YouTubeSettings.REFRESH_TOKEN).ifPresent(token -> {
            try {
                oauth.revoke(token);
            } catch (YouTubeException e) {
                log.info("Could not revoke the YouTube authorization at Google: {}", e.getMessage());
            }
        });
        login.removeSecrets(List.of(YouTubeSettings.CLIENT_ID, YouTubeSettings.CLIENT_SECRET, YouTubeSettings.REFRESH_TOKEN));
        save(settings().withoutAccount());
        tokens.reset();
        YouTubeContentSource contentSource = source.getIfAvailable();
        if (contentSource != null) {
            contentSource.forgetAccount();
        }
    }
}
