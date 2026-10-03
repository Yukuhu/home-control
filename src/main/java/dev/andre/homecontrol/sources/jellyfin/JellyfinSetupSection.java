package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.SetupSection;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.security.LoginService;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnModule(Module.JELLYFIN)
public class JellyfinSetupSection extends SetupSection {

    private static final String PASSWORD = "password";

    /** A Jellyfin app session that could be linked to a device; {@code linkedDeviceId} is blank when unlinked. */
    public record SessionOption(String jellyfinDeviceId, String label, String linkedDeviceId) {
    }

    public record DeviceOption(String id, String name, boolean androidTv, String player) {
    }

    /** What the setup page shows about Jellyfin. Never holds a token. */
    public record View(boolean configured, String serverName, String serverVersion, String serverUrl,
                       String deviceServerUrl, String userName, String mode, boolean deviceAddressLooksLocal,
                       boolean needsLoginPassword, List<DeviceOption> devices) {
    }

    /** The Jellyfin apps open now, to link to devices, or why Jellyfin could not list them. */
    public record Sessions(List<SessionOption> sessions, String error, List<DeviceOption> devices) {
    }

    private final ObjectProvider<JellyfinSetupService> setup;
    private final ObjectProvider<LoginService> login;
    private final ObjectProvider<JellyfinSessions> sessions;
    private final ObjectProvider<DeviceQueries> devices;

    public JellyfinSetupSection(ObjectProvider<JellyfinSetupService> setup, ObjectProvider<LoginService> login,
                               ObjectProvider<JellyfinSessions> sessions, ObjectProvider<DeviceQueries> devices) {
        super("jellyfin", "Jellyfin", Group.CONTENT_SOURCES, 10);
        this.setup = setup;
        this.login = login;
        this.sessions = sessions;
        this.devices = devices;
    }

    @Override
    public View view(URI baseUrl) {
        JellyfinSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, null, null, null, null, null, PASSWORD, false, needsPassword, List.of());
        }
        Optional<JellyfinSettings> settings = service.settings();
        if (settings.isEmpty()) {
            return new View(false, null, null, null, null, null, PASSWORD, false, needsPassword, List.of());
        }
        JellyfinSettings s = settings.orElseThrow();
        return new View(true, s.serverName(), s.serverVersion(), s.serverUrl().toString(),
                s.deviceServerUrl().toString(), s.userName(),
                s.authMode() == JellyfinSettings.AuthMode.API_KEY ? "api-key" : PASSWORD,
                s.deviceAddressLooksLocal(), needsPassword, deviceOptions(s));
    }

    /**
     * Asks Jellyfin which of its apps are open. Not part of {@link #view}: the setup page loads it after it has
     * rendered, as a sleeping NAS can take a quarter of a minute to answer.
     */
    public Sessions sessions() {
        Optional<JellyfinSettings> settings = Optional.ofNullable(setup.getIfAvailable())
                .flatMap(JellyfinSetupService::settings);
        JellyfinSessions jellyfinSessions = sessions.getIfAvailable();
        if (settings.isEmpty() || jellyfinSessions == null) {
            return new Sessions(List.of(), null, List.of());
        }
        JellyfinSettings s = settings.get();
        Map<String, String> linkedBy = new HashMap<>();
        s.sessionLinks().forEach((deviceId, jellyfinDeviceId) -> linkedBy.put(jellyfinDeviceId, deviceId));
        try {
            List<SessionOption> options = jellyfinSessions.controllable().stream()
                    .map(session -> new SessionOption(session.deviceId(),
                            session.deviceName() + " · " + session.client() + " · " + session.remoteAddress(),
                            linkedBy.getOrDefault(session.deviceId(), "")))
                    .toList();
            return new Sessions(options, null, deviceOptions(s));
        } catch (JellyfinException e) {
            return new Sessions(List.of(), e.getMessage(), deviceOptions(s));
        }
    }

    private List<DeviceOption> deviceOptions(JellyfinSettings s) {
        DeviceQueries registered = devices.getIfAvailable();
        return registered == null ? List.of()
                : registered.devices().stream().map(d -> new DeviceOption(d.id(), d.name(),
                        registered.capabilities(d.id()).contains(Capability.ANDROID_APPS),
                        s.player(d.id()).name().toLowerCase(java.util.Locale.ROOT))).toList();
    }
}
