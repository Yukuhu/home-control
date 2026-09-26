package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Connects, checks and disconnects the Jellyfin server; the only writer of its settings and token. */
public class JellyfinSetupService {

    public record ConnectRequest(String serverUrl, String deviceServerUrl, JellyfinSettings.AuthMode mode,
                                 String userName, String password, String apiKey,
                                 String loginPassword, String loginPasswordConfirmation) {
        @Override
        public String toString() {
            return "ConnectRequest[serverUrl=" + serverUrl + ", mode=" + mode + ", userName=" + userName + "]";
        }
    }

    private final JellyfinClient client;
    private final JsonFileSourceSettings sources;
    private final SecretStore secrets;
    private final LoginService login;

    public JellyfinSetupService(JellyfinClient client, JsonFileSourceSettings sources, SecretStore secrets, LoginService login) {
        this.client = client;
        this.sources = sources;
        this.secrets = secrets;
        this.login = login;
    }

    public Optional<JellyfinSettings> settings() {
        return JellyfinSettings.from(sources.get(JellyfinSettings.SOURCE_ID));
    }

    public Optional<JellyfinConnection> connection() {
        return settings().flatMap(settings -> secrets.secret(JellyfinSettings.TOKEN_SECRET)
                .map(token -> new JellyfinConnection(settings.serverUrl(), token, settings.deviceId(), settings.userId())));
    }

    public JellyfinSettings connect(ConnectRequest request, HttpServletRequest http) {
        JellyfinSettings.AuthMode mode = request.mode() == null ? JellyfinSettings.AuthMode.PASSWORD : request.mode();
        // Whichever gate applies (a fresh login password, or this browser's own session) is checked
        // before anything else, including parsing the rest of the request, so a rejected request
        // never touches Jellyfin and never learns whether its other fields would have been valid.
        if (login.loginRequired()) {
            if (!login.isAuthenticated(http)) {
                throw new LoginRequiredException();
            }
        } else {
            login.checkNewPassword(request.loginPassword(), request.loginPasswordConfirmation());
        }
        URI server = JellyfinClient.normalizeServerUrl(request.serverUrl());
        URI deviceServer = request.deviceServerUrl() == null || request.deviceServerUrl().isBlank()
                ? server : JellyfinClient.normalizeServerUrl(request.deviceServerUrl());
        if (request.userName() == null || request.userName().isBlank()) {
            throw new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Enter the Jellyfin user name");
        }
        Optional<JellyfinSettings> previous = settings();
        Optional<String> previousToken = secrets.secret(JellyfinSettings.TOKEN_SECRET);
        String deviceId = previous.map(JellyfinSettings::deviceId)
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));

        JsonNode info = client.publicInfo(server);
        String token;
        JsonNode user;
        if (mode == JellyfinSettings.AuthMode.PASSWORD) {
            JsonNode authenticated = client.authenticateByName(server, deviceId, request.userName().strip(),
                    request.password() == null ? "" : request.password());
            token = authenticated.path("AccessToken").asString("");
            user = authenticated.path("User");
        } else {
            if (request.apiKey() == null || request.apiKey().isBlank()) {
                throw new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Enter the Jellyfin API key");
            }
            token = request.apiKey().strip();
            user = findUser(client.get(new JellyfinConnection(server, token, deviceId, null), "/Users", Map.of()),
                    request.userName().strip());
        }
        if (token.isBlank() || user.path("Id").asString("").isBlank()) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin did not return a usable login");
        }
        String receiver = user.path("Configuration").path("CastReceiverId").asString("");
        JellyfinSettings next = new JellyfinSettings(server, deviceServer, info.path("Id").asString(""),
                info.path("ServerName").asString(""), info.path("Version").asString(""),
                user.path("Id").asString(""), user.path("Name").asString(request.userName().strip()), mode, deviceId,
                receiver.isBlank() ? JellyfinSettings.DEFAULT_CAST_RECEIVER_ID : receiver,
                previous.map(JellyfinSettings::sessionLinks).orElse(Map.of()),
                previous.map(JellyfinSettings::players).orElse(Map.of()));
        try {
            login.storeSecrets(Map.of(JellyfinSettings.TOKEN_SECRET, token), request.loginPassword(),
                    request.loginPasswordConfirmation(), http);
        } catch (RuntimeException e) {
            if (mode == JellyfinSettings.AuthMode.PASSWORD) {
                revokeQuietly(new JellyfinConnection(server, token, deviceId, next.userId()));
            }
            throw e;
        }
        sources.put(JellyfinSettings.SOURCE_ID, next.toMap());
        previous.filter(old -> old.authMode() == JellyfinSettings.AuthMode.PASSWORD)
                .ifPresent(old -> previousToken.filter(oldToken -> !oldToken.equals(token))
                        .ifPresent(oldToken -> revokeQuietly(new JellyfinConnection(old.serverUrl(), oldToken, old.deviceId(), old.userId()))));
        return next;
    }

    /** Session links and other non-secret changes. */
    public void save(JellyfinSettings settings) {
        sources.put(JellyfinSettings.SOURCE_ID, settings.toMap());
    }

    /** Pins a Jellyfin app (by its DeviceId) to one device; a blank device id unlinks it. */
    public void link(String jellyfinDeviceId, String deviceId) {
        JellyfinSettings settings = settings().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
        for (Map.Entry<String, String> entry : settings.sessionLinks().entrySet()) {
            if (entry.getValue().equals(jellyfinDeviceId)) {
                settings = settings.withSessionLink(entry.getKey(), null);
            }
        }
        if (deviceId != null && !deviceId.isBlank()) {
            settings = settings.withSessionLink(deviceId, jellyfinDeviceId);
        }
        save(settings);
    }

    public String check() {
        JellyfinSettings settings = settings().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
        JellyfinConnection connection = connection().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.UNAUTHORIZED, "The Jellyfin token is missing; reconnect Jellyfin"));
        JsonNode info = client.publicInfo(settings.serverUrl());
        JsonNode user = client.get(connection, "/Users/" + JellyfinClient.id(settings.userId()), Map.of());
        return "Connected to " + info.path("ServerName").asString("Jellyfin") + " (Jellyfin "
                + info.path("Version").asString("?") + ") as " + user.path("Name").asString(settings.userName());
    }

    public void disconnect() {
        Optional<JellyfinSettings> settings = settings();
        settings.filter(s -> s.authMode() == JellyfinSettings.AuthMode.PASSWORD)
                .flatMap(ignored -> connection())
                .ifPresent(this::revokeQuietly);
        sources.remove(JellyfinSettings.SOURCE_ID);
        login.removeSecrets(List.of(JellyfinSettings.TOKEN_SECRET));
    }

    private static JsonNode findUser(JsonNode users, String name) {
        for (JsonNode user : users) {
            if (user.path("Name").asString("").equalsIgnoreCase(name)) {
                return user;
            }
        }
        throw new JellyfinException(JellyfinException.Kind.USER_NOT_FOUND, "No Jellyfin user named '" + name + "'");
    }

    private void revokeQuietly(JellyfinConnection connection) {
        try {
            client.post(connection, "/Sessions/Logout", Map.of(), null);
        } catch (JellyfinException _) {
            // best effort: the token stays valid on the server until an admin removes the device
        }
    }
}
