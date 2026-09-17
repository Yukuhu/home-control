package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** Connects, authorizes, tests and disconnects YouTube from the setup page; always a redirect back to it. */
@Controller
@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)
public class YouTubeSetupController {

    private static final String REDIRECT = "redirect:/setup#youtube";

    private final YouTubeSetupService setup;

    public YouTubeSetupController(YouTubeSetupService setup) {
        this.setup = setup;
    }

    @PostMapping("/setup/sources/youtube/connect")
    public String connect(@RequestParam(required = false) String clientId, @RequestParam(required = false) String clientSecret,
                          @RequestParam(required = false) String loginPassword,
                          @RequestParam(required = false) String loginPasswordConfirmation,
                          HttpServletRequest request, RedirectAttributes redirect) {
        YouTubeSetupService.ConnectRequest connectRequest =
                new YouTubeSetupService.ConnectRequest(clientId, clientSecret, loginPassword, loginPasswordConfirmation);
        try {
            setup.connect(connectRequest, request);
            redirect.addFlashAttribute("youtubeMessage", "Enter the code on your phone");
        } catch (YouTubeException | PasswordRejectedException | LoginRequiredException e) {
            redirect.addFlashAttribute("youtubeError", e.getMessage());
            redirect.addFlashAttribute("youtubeForm", Map.of("clientId", clientId == null ? "" : clientId));
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/authorize")
    public String authorize(RedirectAttributes redirect) {
        try {
            setup.authorize();
            redirect.addFlashAttribute("youtubeMessage", "Enter the code on your phone");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute("youtubeError", e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/cancel")
    public String cancel(RedirectAttributes redirect) {
        try {
            setup.cancel();
            redirect.addFlashAttribute("youtubeMessage", "Cancelled");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute("youtubeError", e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/test")
    public String test(RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("youtubeMessage", setup.check());
        } catch (YouTubeException e) {
            redirect.addFlashAttribute("youtubeError", e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/disconnect")
    public String disconnect(RedirectAttributes redirect) {
        try {
            setup.disconnect();
            redirect.addFlashAttribute("youtubeMessage", "YouTube disconnected");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute("youtubeError", e.getMessage());
        }
        return REDIRECT;
    }

    @GetMapping("/setup/sources/youtube/authorization")
    public String authorization(Model model, HttpServletResponse response) {
        YouTubeAuthorizationService.Status status = setup.authorizationStatus();
        model.addAttribute("authorization", status);
        if (status.state() == YouTubeAuthorizationService.State.CONNECTED) {
            response.setHeader("HX-Refresh", "true");
        }
        return "fragments/youtube-setup :: authorization";
    }
}
