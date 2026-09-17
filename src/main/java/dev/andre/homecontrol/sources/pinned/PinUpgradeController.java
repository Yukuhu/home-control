package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The play sheet's "paste a link to open this title directly". */
@RestController
@ConditionalOnProperty(name = "home-control.pinned.enabled", havingValue = "true", matchIfMissing = true)
public class PinUpgradeController {

    private static final Logger log = LoggerFactory.getLogger(PinUpgradeController.class);

    private final PinnedShortcuts pins;

    public PinUpgradeController(PinnedShortcuts pins) {
        this.pins = pins;
    }

    @PostMapping(path = "/setup/sources/pinned/upgrade", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> upgrade(@RequestParam String url, @RequestParam String upgradeOf) {
        try {
            Pin pin = pins.addUpgrade(url, upgradeOf);
            return ResponseEntity.ok(Map.of("id", pin.id(), "title", pin.title(),
                    "message", "Pinned " + pin.title() + ". It now opens directly."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (StorageException e) {
            log.warn("Could not save a pinned link", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not save the pinned link"));
        }
    }
}
