package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Connects, tests and disconnects TMDB from the setup page; always a redirect back to it. */
@Controller
@ConditionalOnProperty(name = "home-control.tmdb.enabled", havingValue = "true", matchIfMissing = true)
public class TmdbSetupController {

    private static final String MESSAGE = "tmdbMessage";
    private static final String ERROR = "tmdbError";
    private static final String REDIRECT = "redirect:/setup#tmdb";

    private final TmdbSetupService setup;

    public TmdbSetupController(TmdbSetupService setup) {
        this.setup = setup;
    }

    @PostMapping("/setup/sources/tmdb")
    public String connect(@RequestParam(required = false) String credential,
                          @RequestParam(required = false) String loginPassword,
                          @RequestParam(required = false) String loginPasswordConfirmation,
                          HttpServletRequest request, RedirectAttributes redirect) {
        try {
            setup.connect(new TmdbSetupService.ConnectRequest(credential, loginPassword, loginPasswordConfirmation), request);
            redirect.addFlashAttribute(MESSAGE, "TMDB connected");
        } catch (ContentSourceException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (PasswordRejectedException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (LoginRequiredException _) {
            redirect.addFlashAttribute(ERROR, "Log in again to change TMDB");
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/tmdb/test")
    public String test(RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute(MESSAGE, setup.check());
        } catch (ContentSourceException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/tmdb/disconnect")
    public String disconnect(RedirectAttributes redirect) {
        setup.disconnect();
        redirect.addFlashAttribute(MESSAGE, "TMDB disconnected");
        return REDIRECT;
    }
}
