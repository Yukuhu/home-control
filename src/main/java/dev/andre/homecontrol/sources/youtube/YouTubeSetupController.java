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

import java.util.List;
import java.util.Map;

/** YouTube setup, including the browser OAuth redirect and callback. */
@Controller
@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)
public class YouTubeSetupController {

    private static final String REDIRECT = "redirect:/setup#youtube";
    private static final String ERROR = "youtubeError";
    private static final String MESSAGE = "youtubeMessage";

    private final YouTubeSetupService setup;

    public YouTubeSetupController(YouTubeSetupService setup) {
        this.setup = setup;
    }

    @PostMapping("/setup/sources/youtube/browser/connect")
    public String connectBrowser(@RequestParam(required = false) String clientId,
                                 @RequestParam(required = false) String clientSecret,
                                 @RequestParam(required = false) String loginPassword,
                                 @RequestParam(required = false) String loginPasswordConfirmation,
                                 HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        privateResponse(response);
        try {
            return "redirect:" + setup.connectBrowser(new YouTubeSetupService.ConnectRequest(
                    clientId, clientSecret, loginPassword, loginPasswordConfirmation), request);
        } catch (YouTubeException | PasswordRejectedException | LoginRequiredException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
            redirect.addFlashAttribute("youtubeForm", Map.of("clientId", clientId == null ? "" : clientId));
            return REDIRECT;
        }
    }

    @PostMapping("/setup/sources/youtube/browser/authorize")
    public String authorizeBrowser(HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        privateResponse(response);
        try {
            return "redirect:" + setup.authorizeBrowser(request);
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
            return REDIRECT;
        }
    }

    @GetMapping(YouTubeOAuthCallback.PATH)
    public String callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                           @RequestParam(required = false) String error, HttpServletRequest request,
                           HttpServletResponse response, RedirectAttributes redirect) {
        privateResponse(response);
        completeCallback(state, code, error, request, redirect);
        return REDIRECT;
    }

    private void completeCallback(String state, String code, String error, HttpServletRequest request,
                                  RedirectAttributes redirect) {
        // Spring also maps HEAD to GET. Only a real callback navigation may consume a grant.
        if (!"GET".equals(request.getMethod())) return;
        try {
            var status = setup.completeBrowser(request, state, code, error);
            redirect.addFlashAttribute(status.state() == YouTubeAuthorizationService.State.CONNECTED
                    ? MESSAGE : ERROR, status.message());
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
    }

    private static void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
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
            redirect.addFlashAttribute(MESSAGE, "Enter the code on your phone");
        } catch (YouTubeException | PasswordRejectedException | LoginRequiredException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
            redirect.addFlashAttribute("youtubeForm", Map.of("clientId", clientId == null ? "" : clientId));
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/authorize")
    public String authorize(RedirectAttributes redirect) {
        try {
            setup.authorize();
            redirect.addFlashAttribute(MESSAGE, "Enter the code on your phone");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/cancel")
    public String cancel(RedirectAttributes redirect) {
        try {
            setup.cancel();
            redirect.addFlashAttribute(MESSAGE, "Cancelled");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/test")
    public String test(RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute(MESSAGE, setup.check());
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/disconnect")
    public String disconnect(RedirectAttributes redirect) {
        try {
            setup.disconnect();
            redirect.addFlashAttribute(MESSAGE, "YouTube disconnected");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/playlists/load")
    public String loadPlaylists(RedirectAttributes redirect) {
        try {
            int found = setup.loadPlaylists().size();
            redirect.addFlashAttribute(MESSAGE, "Found " + found + " playlists");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/playlists")
    public String choosePlaylists(@RequestParam(name = "playlist", required = false) List<String> playlist,
                                  RedirectAttributes redirect) {
        try {
            setup.choosePlaylists(playlist == null ? List.of() : playlist);
            redirect.addFlashAttribute(MESSAGE, "Playlists saved");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/youtube/watch-later")
    public String watchLater(@RequestParam boolean enabled, RedirectAttributes redirect) {
        try {
            setup.setWatchLater(enabled);
            redirect.addFlashAttribute(MESSAGE, enabled ? "Watch Later shown" : "Watch Later hidden");
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    /** Best-effort YouTube Cast per Cast device; off unless switched on here. */
    @PostMapping("/setup/sources/youtube/lounge")
    public String lounge(@RequestParam(required = false) String device, @RequestParam boolean enabled,
                         RedirectAttributes redirect) {
        try {
            String name = setup.setLounge(device, enabled);
            redirect.addFlashAttribute(MESSAGE, "YouTube Cast switched " + (enabled ? "on" : "off") + " for " + name);
        } catch (YouTubeException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
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
