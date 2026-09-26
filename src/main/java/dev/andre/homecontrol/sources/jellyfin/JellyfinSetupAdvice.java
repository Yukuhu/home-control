package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)
public class JellyfinSetupAdvice {

    private static final String PASSWORD = "password";

    /** A Jellyfin app session that could be linked to a device; {@code linkedDeviceId} is blank when unlinked. */
    public record SessionOption(String jellyfinDeviceId, String label, String linkedDeviceId) {
    }

    public record DeviceOption(String id, String name, boolean androidTv, String player) {
    }

    /** What the setup page shows about Jellyfin. Never holds a token. */
    public record View(boolean configured, String serverName, String serverVersion, String serverUrl,
                       String deviceServerUrl, String userName, String mode, boolean deviceAddressLooksLocal,
                       boolean needsLoginPassword, List<SessionOption> sessions, String sessionsError,
                       List<DeviceOption> devices) {
    }

    private final ObjectProvider<JellyfinSetupService> setup;
    private final ObjectProvider<LoginService> login;
    private final ObjectProvider<JellyfinSessions> sessions;
    private final ObjectProvider<DeviceManager> devices;

    public JellyfinSetupAdvice(ObjectProvider<JellyfinSetupService> setup, ObjectProvider<LoginService> login,
                               ObjectProvider<JellyfinSessions> sessions, ObjectProvider<DeviceManager> devices) {
        this.setup = setup;
        this.login = login;
        this.sessions = sessions;
        this.devices = devices;
    }

    @ModelAttribute("jellyfin")
    public View jellyfin() {
        JellyfinSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, null, null, null, null, null, PASSWORD, false, needsPassword,
                    List.of(), null, List.of());
        }
        Optional<JellyfinSettings> settings = service.settings();
        if (settings.isEmpty()) {
            return new View(false, null, null, null, null, null, PASSWORD, false, needsPassword,
                    List.of(), null, List.of());
        }
        JellyfinSettings s = settings.orElseThrow();
        List<SessionOption> sessionOptions;
        String sessionsError;
        JellyfinSessions jellyfinSessions = sessions.getIfAvailable();
        if (jellyfinSessions == null) {
            sessionOptions = List.of();
            sessionsError = null;
        } else {
            Map<String, String> linkedBy = new HashMap<>();
            s.sessionLinks().forEach((deviceId, jellyfinDeviceId) -> linkedBy.put(jellyfinDeviceId, deviceId));
            try {
                sessionOptions = jellyfinSessions.controllable().stream()
                        .map(session -> new SessionOption(session.deviceId(),
                                session.deviceName() + " · " + session.client() + " · " + session.remoteAddress(),
                                linkedBy.getOrDefault(session.deviceId(), "")))
                        .toList();
                sessionsError = null;
            } catch (JellyfinException e) {
                sessionOptions = List.of();
                sessionsError = e.getMessage();
            }
        }
        DeviceManager deviceManager = devices.getIfAvailable();
        List<DeviceOption> deviceOptions = deviceManager == null ? List.of()
                : deviceManager.devices().stream().map(d -> new DeviceOption(d.id(), d.name(), d.hasAdapter("androidtv"),
                        s.player(d.id()).name().toLowerCase(java.util.Locale.ROOT))).toList();
        return new View(true, s.serverName(), s.serverVersion(), s.serverUrl().toString(),
                s.deviceServerUrl().toString(), s.userName(),
                s.authMode() == JellyfinSettings.AuthMode.API_KEY ? "api-key" : PASSWORD,
                s.deviceAddressLooksLocal(), needsPassword, sessionOptions, sessionsError, deviceOptions);
    }
}
