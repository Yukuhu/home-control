package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)
public class JellyfinSetupAdvice {

    /** What the setup page shows about Jellyfin. Never holds a token. */
    public record View(boolean configured, String serverName, String serverVersion, String serverUrl,
                       String deviceServerUrl, String userName, String mode, boolean deviceAddressLooksLocal,
                       boolean needsLoginPassword) {
    }

    private final ObjectProvider<JellyfinSetupService> setup;
    private final ObjectProvider<LoginService> login;

    public JellyfinSetupAdvice(ObjectProvider<JellyfinSetupService> setup, ObjectProvider<LoginService> login) {
        this.setup = setup;
        this.login = login;
    }

    @ModelAttribute("jellyfin")
    public View jellyfin() {
        JellyfinSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, null, null, null, null, null, "password", false, needsPassword);
        }
        return service.settings()
                .map(s -> new View(true, s.serverName(), s.serverVersion(), s.serverUrl().toString(),
                        s.deviceServerUrl().toString(), s.userName(),
                        s.authMode() == JellyfinSettings.AuthMode.API_KEY ? "api-key" : "password",
                        s.deviceAddressLooksLocal(), needsPassword))
                .orElseGet(() -> new View(false, null, null, null, null, null, "password", false, needsPassword));
    }
}
