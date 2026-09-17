package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/** Connects, tests and disconnects Jellyfin from the setup page; always a redirect back to it. */
@Controller
@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)
public class JellyfinSetupController {

    private final JellyfinSetupService setup;

    public JellyfinSetupController(JellyfinSetupService setup) {
        this.setup = setup;
    }

    @PostMapping("/setup/sources/jellyfin")
    public String connect(@RequestParam(required = false) String serverUrl,
                          @RequestParam(required = false) String deviceServerUrl,
                          @RequestParam(required = false, defaultValue = "password") String mode,
                          @RequestParam(required = false) String userName,
                          @RequestParam(required = false) String password,
                          @RequestParam(required = false) String apiKey,
                          @RequestParam(required = false) String loginPassword,
                          @RequestParam(required = false) String loginPasswordConfirmation,
                          HttpServletRequest request, RedirectAttributes redirect) {
        JellyfinSettings.AuthMode authMode = "api-key".equals(mode)
                ? JellyfinSettings.AuthMode.API_KEY : JellyfinSettings.AuthMode.PASSWORD;
        JellyfinSetupService.ConnectRequest connectRequest = new JellyfinSetupService.ConnectRequest(
                serverUrl, deviceServerUrl, authMode, userName, password, apiKey, loginPassword, loginPasswordConfirmation);
        try {
            JellyfinSettings connected = setup.connect(connectRequest, request);
            redirect.addFlashAttribute("jellyfinMessage", "Connected to " + connected.serverName() + " as " + connected.userName());
        } catch (JellyfinException | PasswordRejectedException | LoginRequiredException | IllegalStateException e) {
            redirect.addFlashAttribute("jellyfinError", e.getMessage());
            Map<String, String> form = new LinkedHashMap<>();
            form.put("serverUrl", serverUrl == null ? "" : serverUrl);
            form.put("deviceServerUrl", deviceServerUrl == null ? "" : deviceServerUrl);
            form.put("mode", mode == null ? "" : mode);
            form.put("userName", userName == null ? "" : userName);
            redirect.addFlashAttribute("jellyfinForm", form);
        }
        return "redirect:/setup";
    }

    @PostMapping("/setup/sources/jellyfin/test")
    public String test(RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("jellyfinMessage", setup.check());
        } catch (JellyfinException e) {
            redirect.addFlashAttribute("jellyfinError", e.getMessage());
        }
        return "redirect:/setup";
    }

    @PostMapping("/setup/sources/jellyfin/disconnect")
    public String disconnect(RedirectAttributes redirect) {
        setup.disconnect();
        redirect.addFlashAttribute("jellyfinMessage", "Jellyfin disconnected");
        return "redirect:/setup";
    }

    @PostMapping("/setup/sources/jellyfin/links")
    public String link(@RequestParam String session, @RequestParam(required = false) String device,
                       RedirectAttributes redirect) {
        try {
            setup.link(session, device);
            redirect.addFlashAttribute("jellyfinMessage", "Link saved");
        } catch (JellyfinException e) {
            redirect.addFlashAttribute("jellyfinError", e.getMessage());
        }
        return "redirect:/setup";
    }
}
