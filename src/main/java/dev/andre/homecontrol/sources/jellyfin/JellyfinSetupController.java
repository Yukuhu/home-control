package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.device.DeviceManager;
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

    private static final String MESSAGE = "jellyfinMessage";
    private static final String ERROR = "jellyfinError";
    private static final String REDIRECT = "redirect:/setup";

    private final JellyfinSetupService setup;
    private final DeviceManager devices;

    public JellyfinSetupController(JellyfinSetupService setup, DeviceManager devices) {
        this.devices = devices;
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
            redirect.addFlashAttribute(MESSAGE, "Connected to " + connected.serverName() + " as " + connected.userName());
        } catch (JellyfinException | PasswordRejectedException | LoginRequiredException | IllegalStateException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
            Map<String, String> form = new LinkedHashMap<>();
            form.put("serverUrl", serverUrl == null ? "" : serverUrl);
            form.put("deviceServerUrl", deviceServerUrl == null ? "" : deviceServerUrl);
            form.put("mode", mode == null ? "" : mode);
            form.put("userName", userName == null ? "" : userName);
            redirect.addFlashAttribute("jellyfinForm", form);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/jellyfin/test")
    public String test(RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute(MESSAGE, setup.check());
        } catch (JellyfinException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/jellyfin/disconnect")
    public String disconnect(RedirectAttributes redirect) {
        setup.disconnect();
        redirect.addFlashAttribute(MESSAGE, "Jellyfin disconnected");
        return REDIRECT;
    }

    @PostMapping("/setup/sources/jellyfin/players")
    public String player(@RequestParam String device, @RequestParam String player, RedirectAttributes redirect) {
        try {
            if (devices.device(device).filter(d -> d.hasAdapter("androidtv")).isEmpty()) {
                throw new IllegalArgumentException("Choose a paired Shield or Android TV device");
            }
            JellyfinSettings.Player selected = switch (player) {
                case "jellyfin" -> JellyfinSettings.Player.JELLYFIN;
                case "vlc" -> JellyfinSettings.Player.VLC;
                default -> throw new IllegalArgumentException("Choose Jellyfin app or VLC");
            };
            JellyfinSettings settings = setup.settings().orElseThrow(() ->
                    new IllegalArgumentException("Connect Jellyfin before choosing a player"));
            setup.save(settings.withPlayer(device, selected));
            redirect.addFlashAttribute(MESSAGE, "Player preference saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/jellyfin/links")
    public String link(@RequestParam String session, @RequestParam(required = false) String device,
                       RedirectAttributes redirect) {
        try {
            setup.link(session, device);
            redirect.addFlashAttribute(MESSAGE, "Link saved");
        } catch (JellyfinException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }
}
