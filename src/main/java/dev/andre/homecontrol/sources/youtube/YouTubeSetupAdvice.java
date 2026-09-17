package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)
public class YouTubeSetupAdvice {

    /** What the setup page shows about YouTube. Never holds a secret or a device code. */
    public record View(boolean hasClient, boolean connected, boolean revoked, String channelTitle,
                       YouTubeAuthorizationService.Status authorization, boolean needsLoginPassword) {
    }

    private final ObjectProvider<YouTubeSetupService> setup;
    private final ObjectProvider<LoginService> login;

    public YouTubeSetupAdvice(ObjectProvider<YouTubeSetupService> setup, ObjectProvider<LoginService> login) {
        this.setup = setup;
        this.login = login;
    }

    @ModelAttribute("youtube")
    public View youtube() {
        YouTubeSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, false, false, null,
                    YouTubeAuthorizationService.Status.of(YouTubeAuthorizationService.State.IDLE, null), needsPassword);
        }
        YouTubeSettings settings = service.settings();
        return new View(service.hasClient(), service.connected(), service.revoked(), settings.channelTitle(),
                service.authorizationStatus(), needsPassword);
    }
}
