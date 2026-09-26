package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Adds, renames, reorders and removes pinned shortcuts from the setup page; always a redirect back to it. */
@Controller
@ConditionalOnProperty(name = "home-control.pinned.enabled", havingValue = "true", matchIfMissing = true)
public class PinnedSetupController {

    private static final String MESSAGE = "pinnedMessage";
    private static final String ERROR = "pinnedError";
    private static final String SAVE_ERROR_PREFIX = "Could not save pinned links: ";
    private static final String REDIRECT = "redirect:/setup#pinned";

    private static final Logger log = LoggerFactory.getLogger(PinnedSetupController.class);

    private final PinnedShortcuts pins;

    public PinnedSetupController(PinnedShortcuts pins) {
        this.pins = pins;
    }

    @PostMapping("/setup/sources/pinned")
    public String add(@RequestParam(required = false) String url, @RequestParam(required = false) String title,
                      RedirectAttributes redirect) {
        try {
            Pin pin = pins.add(url, title);
            redirect.addFlashAttribute(MESSAGE, "Pinned " + pin.title());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn("Could not save pinned links", e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR_PREFIX + e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/pinned/{id}/title")
    public String rename(@PathVariable String id, @RequestParam(required = false) String title,
                         RedirectAttributes redirect) {
        try {
            pins.rename(id, title);
            redirect.addFlashAttribute(MESSAGE, "Renamed to " + (title == null ? "" : title.strip()));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn("Could not save pinned links", e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR_PREFIX + e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/pinned/{id}/move")
    public String move(@PathVariable String id, @RequestParam String direction, RedirectAttributes redirect) {
        if (!"up".equals(direction) && !"down".equals(direction)) {
            redirect.addFlashAttribute(ERROR, "Choose up or down");
            return REDIRECT;
        }
        try {
            pins.move(id, "up".equals(direction));
            redirect.addFlashAttribute(MESSAGE, "Order saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn("Could not save pinned links", e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR_PREFIX + e.getMessage());
        }
        return REDIRECT;
    }

    @PostMapping("/setup/sources/pinned/{id}/remove")
    public String remove(@PathVariable String id, RedirectAttributes redirect) {
        try {
            String title = pins.find(id).map(Pin::title)
                    .orElseThrow(() -> new IllegalArgumentException("No pinned link " + id));
            pins.remove(id);
            redirect.addFlashAttribute(MESSAGE, "Removed " + title);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute(ERROR, e.getMessage());
        } catch (StorageException e) {
            log.warn("Could not save pinned links", e);
            redirect.addFlashAttribute(ERROR, SAVE_ERROR_PREFIX + e.getMessage());
        }
        return REDIRECT;
    }
}
