package dev.andre.homecontrol.sources.jellyfin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Re-serves Jellyfin artwork so the browser never talks to Jellyfin directly and never sees a
 * token (the upstream request is anonymous, see {@link JellyfinClient#image}).
 */
@Controller
@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)
public class JellyfinImageController {

    private static final Set<String> TYPES = Set.of("Primary", "Thumb", "Backdrop", "Logo");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final int DEFAULT_WIDTH = 480;
    private static final int MAX_WIDTH = 1920;

    private final JellyfinClient client;
    private final JellyfinSetupService setup;

    public JellyfinImageController(JellyfinClient client, JellyfinSetupService setup) {
        this.client = client;
        this.setup = setup;
    }

    @GetMapping("/sources/jellyfin/images/{itemId}/{type}")
    public ResponseEntity<byte[]> image(@PathVariable String itemId, @PathVariable String type,
                                        @RequestParam(required = false) String tag,
                                        @RequestParam(required = false) Integer width) {
        String id;
        try {
            id = JellyfinClient.id(itemId);
        } catch (IllegalArgumentException _) {
            return ResponseEntity.badRequest().build();
        }
        if (!TYPES.contains(type) || (tag != null && !TAG.matcher(tag).matches())) {
            return ResponseEntity.badRequest().build();
        }
        int clampedWidth = width == null || width < 1 ? DEFAULT_WIDTH : Math.min(width, MAX_WIDTH);

        Optional<JellyfinSettings> settings = setup.settings();
        if (settings.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<JellyfinClient.Image> image;
        try {
            image = client.image(settings.get().serverUrl(), id, type, tag, clampedWidth);
        } catch (JellyfinException _) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (image.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // A tag names an immutable version of the image; without one, Jellyfin may replace it any time.
        String cacheControl = tag != null ? "private, max-age=31536000, immutable" : "private, max-age=3600";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.get().contentType()))
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .header("X-Content-Type-Options", "nosniff")
                .body(image.get().bytes());
    }
}
