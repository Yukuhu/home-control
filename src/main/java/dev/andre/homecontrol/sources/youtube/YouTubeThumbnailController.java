package dev.andre.homecontrol.sources.youtube;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Re-serves a video's thumbnail so the browser never talks to Google directly and never sees a token. */
@RestController
@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)
public class YouTubeThumbnailController {

    // Google's thumbnail CDN only ever serves these; anything else is treated as a failure rather
    // than trusted and passed through, so a compromised or misbehaving upstream can't get the
    // browser to render or sniff an unexpected content type as an image.
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final YouTubeHttp http;
    private final YouTubeProperties properties;

    public YouTubeThumbnailController(YouTubeHttp http, YouTubeProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @GetMapping("/sources/youtube/thumbnails/{videoId}")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String videoId) {
        if (!YouTubeVideo.validId(videoId)) {
            return ResponseEntity.badRequest().body("Not a YouTube video id".getBytes());
        }
        YouTubeHttp.Response response;
        try {
            response = http.get(YouTubeHttp.uri(properties.thumbnailBaseUrl(), "/vi/" + videoId + "/mqdefault.jpg", Map.of()), Map.of());
        } catch (YouTubeException e) {
            return ResponseEntity.status(502).body("Could not load the thumbnail".getBytes());
        }
        if (response.status() == 404) {
            return ResponseEntity.notFound().build();
        }
        if (!response.ok()) {
            return ResponseEntity.status(502).body("Could not load the thumbnail".getBytes());
        }
        String contentType = baseType(response.contentType());
        if (!ALLOWED_TYPES.contains(contentType)) {
            return ResponseEntity.status(502).body("Could not load the thumbnail".getBytes());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(response.body());
    }

    private static String baseType(String contentType) {
        String value = contentType == null ? "" : contentType;
        int semicolon = value.indexOf(';');
        return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
