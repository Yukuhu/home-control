package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.tmdb.enabled", havingValue = "true", matchIfMissing = true)
public class TmdbSetupAdvice {

    /** What the setup page shows about TMDB. Never holds a credential. */
    public record View(boolean configured, String credentialKind, boolean needsLoginPassword) {
    }

    private final ObjectProvider<TmdbSetupService> setup;
    private final ObjectProvider<LoginService> login;

    public TmdbSetupAdvice(ObjectProvider<TmdbSetupService> setup, ObjectProvider<LoginService> login) {
        this.setup = setup;
        this.login = login;
    }

    @ModelAttribute("tmdb")
    public View tmdb() {
        TmdbSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, null, needsPassword);
        }
        return service.settings()
                .map(settings -> new View(true,
                        settings.credentialKind() == TmdbCredential.Kind.BEARER ? "read access token" : "API key",
                        needsPassword))
                .orElseGet(() -> new View(false, null, needsPassword));
    }
}
