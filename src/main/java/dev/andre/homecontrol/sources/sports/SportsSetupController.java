package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/** Adds/removes calendars and sets the household time zone from the setup page; always redirects back to it. */
@Controller
@ConditionalOnProperty(name = "home-control.sports.enabled", havingValue = "true", matchIfMissing = true)
public class SportsSetupController {

    private static final String MESSAGE = "sportsMessage";
    private static final String SAVE_ERROR = "Could not save sports settings";
    private static final String REDIRECT = "redirect:/setup#sports";
    private static final String ERROR = "sportsError";

    private static final Logger log = LoggerFactory.getLogger(SportsSetupController.class);

    private final SportsCalendars calendars;
    private final SportsSettingsService settings;
    private final SportsTimeZones zones;

    public SportsSetupController(SportsCalendars calendars, SportsSettingsService settings, SportsTimeZones zones) {
        this.calendars = calendars;
        this.settings = settings;
        this.zones = zones;
    }

    @PostMapping("/setup/sources/sports/calendars")
    public String add(@RequestParam(required = false) String url, @RequestParam(required = false) String label,
                      @RequestParam(required = false) String loginPassword,
                      @RequestParam(required = false) String loginPasswordConfirmation,
                      HttpServletRequest request, RedirectAttributes redirect) {
        try {
            SportsSettings.CalendarEntry entry = calendars.add(
                    new SportsCalendars.AddCalendar(url, label, loginPassword, loginPasswordConfirmation), request);
            redirect.addFlashAttribute(MESSAGE, "Added " + entry.label());
        } catch (LoginRequiredException _) {
            failedAdd(redirect, label, "Log in again to change sources");
        } catch (IllegalArgumentException | ContentSourceException | PasswordRejectedException | IllegalStateException e) {
            failedAdd(redirect, label, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            failedAdd(redirect, label, SAVE_ERROR);
        }
        return REDIRECT;
    }

    private void failedAdd(RedirectAttributes redirect, String label, String message) {
        redirect.addFlashAttribute(ERROR, message);
        redirect.addFlashAttribute("sportsForm", Map.of("label", label == null ? "" : label));
    }

    @PostMapping("/setup/sources/sports/calendars/{id}/remove")
    public String remove(@PathVariable String id, RedirectAttributes redirect) {
        try {
            SportsSettings.CalendarEntry entry = calendars.remove(id);
            redirect.addFlashAttribute(MESSAGE, "Removed " + entry.label());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/sports/time-zone")
    public String timeZone(@RequestParam(required = false) String timeZone, RedirectAttributes redirect) {
        try {
            String stripped = timeZone == null ? "" : timeZone.strip();
            if (!stripped.isEmpty() && SportsTimeZones.parse(stripped).isEmpty()) {
                redirect.addFlashAttribute(ERROR, "Use a time zone such as Europe/Berlin");
                return REDIRECT;
            }
            String stored = stripped.isEmpty() ? null : stripped;
            settings.update(s -> s.withTimeZone(stored));
            String message = stored != null
                    ? "Times are shown in " + stored
                    : "Times are shown in " + zones.effective() + " (default)";
            redirect.addFlashAttribute(MESSAGE, message);
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/sports/providers")
    public String providers(@RequestParam MultiValueMap<String, String> parameters, RedirectAttributes flash) {
        Map<String, String> mapping = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (name.startsWith("provider:") && !values.isEmpty()) {
                mapping.put(name.substring("provider:".length()), values.getFirst());
            }
        });
        try {
            settings.update(current -> SportsProviders.apply(current, mapping));
            flash.addFlashAttribute(MESSAGE,
                    "Saved. These are your own settings; Home Control does not check broadcast rights.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn(SAVE_ERROR, e);
            flash.addFlashAttribute(ERROR, SAVE_ERROR);
        }
        return "redirect:/setup#sports-providers";
    }
}
