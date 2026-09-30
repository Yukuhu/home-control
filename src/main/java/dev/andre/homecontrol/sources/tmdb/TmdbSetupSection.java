package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.SetupSection;
import dev.andre.homecontrol.security.LoginService;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnModule(Module.TMDB)
public class TmdbSetupSection extends SetupSection {

    /** What the setup page shows about TMDB. Never holds a credential. */
    public record View(boolean configured, String credentialKind, boolean needsLoginPassword) {
    }

    private final ObjectProvider<TmdbSetupService> setup;
    private final ObjectProvider<LoginService> login;

    public TmdbSetupSection(ObjectProvider<TmdbSetupService> setup, ObjectProvider<LoginService> login) {
        super("tmdb", "Movies & series", Group.CONTENT_SOURCES, 30);
        this.setup = setup;
        this.login = login;
    }

    @Override
    public View view(URI baseUrl) {
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
