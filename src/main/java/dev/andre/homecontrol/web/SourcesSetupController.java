package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.content.StoredRailPreferences;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.RailDescriptor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Content source and rail preference forms on the setup page (spec D4). Always redirects back there. */
@Controller
public class SourcesSetupController {

    private static final String REDIRECT = "redirect:/setup#sources";
    private static final String ERROR = "sourcesError";
    private static final String MESSAGE = "sourcesMessage";

    private final SourcePreferencesService prefs;
    private final StoredRailPreferences rails;
    private final ContentSources sources;

    public SourcesSetupController(SourcePreferencesService prefs, StoredRailPreferences rails, ContentSources sources) {
        this.prefs = prefs;
        this.rails = rails;
        this.sources = sources;
    }

    @PostMapping("/setup/sources/preferences/{sourceId}/enabled")
    public String enabled(@PathVariable String sourceId, @RequestParam boolean enabled, RedirectAttributes redirect) {
        updateEnabled(sourceId, enabled, redirect);
        return REDIRECT;
    }

    private void updateEnabled(String sourceId, boolean enabled, RedirectAttributes redirect) {
        ContentSource source = sources.find(sourceId).orElse(null);
        if (source == null) {
            redirect.addFlashAttribute(ERROR, "No content source " + sourceId);
            return;
        }
        try {
            prefs.update(p -> p.withSourceEnabled(sourceId, enabled));
            redirect.addFlashAttribute(MESSAGE, enabled
                    ? source.displayName() + " is shown on the dashboard"
                    : source.displayName() + " is hidden from the dashboard and search");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
    }

    @PostMapping("/setup/sources/preferences/{sourceId}/interval")
    public String interval(@PathVariable String sourceId, @RequestParam(required = false) String minutes,
                           RedirectAttributes redirect) {
        updateInterval(sourceId, minutes, redirect);
        return REDIRECT;
    }

    private void updateInterval(String sourceId, String minutes, RedirectAttributes redirect) {
        ContentSource source = sources.find(sourceId).orElse(null);
        if (source == null) {
            redirect.addFlashAttribute(ERROR, "No content source " + sourceId);
            return;
        }
        Integer parsed;
        if (minutes == null || minutes.isBlank()) {
            parsed = null;
        } else {
            try {
                parsed = Integer.parseInt(minutes.trim());
            } catch (NumberFormatException _) {
                redirect.addFlashAttribute(ERROR, "Refresh every 1 to 1440 minutes");
                return;
            }
        }
        try {
            prefs.update(p -> p.withRefreshMinutes(sourceId, parsed));
            redirect.addFlashAttribute(MESSAGE, parsed != null
                    ? source.displayName() + " refreshes every " + parsed + " minutes"
                    : source.displayName() + " uses its default refresh interval");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
    }

    @PostMapping("/setup/sources/preferences/rails/move")
    public String move(@RequestParam String rail, @RequestParam String direction, RedirectAttributes redirect) {
        moveRail(rail, direction, redirect);
        return REDIRECT;
    }

    private void moveRail(String rail, String direction, RedirectAttributes redirect) {
        if (!direction.equals("up") && !direction.equals("down")) {
            redirect.addFlashAttribute(ERROR, "Choose up or down");
            return;
        }
        List<String> order = new ArrayList<>(railKeys());
        int index = order.indexOf(rail);
        if (index < 0) {
            redirect.addFlashAttribute(ERROR, "No rail " + rail);
            return;
        }
        int swapWith = direction.equals("up") ? index - 1 : index + 1;
        if (swapWith >= 0 && swapWith < order.size()) {
            Collections.swap(order, index, swapWith);
        }
        try {
            prefs.update(p -> p.withRailOrder(order));
            redirect.addFlashAttribute(MESSAGE, "Rail order saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
    }

    @PostMapping("/setup/sources/preferences/rails/visibility")
    public String visibility(@RequestParam String rail, @RequestParam boolean visible, RedirectAttributes redirect) {
        updateVisibility(rail, visible, redirect);
        return REDIRECT;
    }

    private void updateVisibility(String rail, boolean visible, RedirectAttributes redirect) {
        String title = rails.allRailsInOrder(sources.all()).stream()
                .filter(d -> key(d).equals(rail))
                .map(RailDescriptor::title)
                .findFirst()
                .orElse(null);
        if (title == null) {
            redirect.addFlashAttribute(ERROR, "No rail " + rail);
            return;
        }
        try {
            prefs.update(p -> p.withRailVisible(rail, visible));
            redirect.addFlashAttribute(MESSAGE, visible ? title + " is shown" : title + " is hidden");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
    }

    @PostMapping("/setup/sources/preferences/locale")
    public String locale(@RequestParam String locale, @RequestParam String region,
                         @RequestParam(required = false) List<String> providers, RedirectAttributes redirect) {
        List<String> selected = providers == null ? List.of() : providers;
        try {
            prefs.update(p -> p.withLocale(locale, region, selected));
            redirect.addFlashAttribute(MESSAGE, "Language and services saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        }
        return REDIRECT;
    }

    private List<String> railKeys() {
        return rails.allRailsInOrder(sources.all()).stream().map(SourcesSetupController::key).toList();
    }

    private static String key(RailDescriptor descriptor) {
        return descriptor.sourceId() + "/" + descriptor.id();
    }
}
