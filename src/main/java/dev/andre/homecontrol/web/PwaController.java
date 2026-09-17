package dev.andre.homecontrol.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serves the web app manifest that lets a phone install the dashboard as an app. */
@RestController
public class PwaController {

    @GetMapping(path = "/manifest.webmanifest", produces = "application/manifest+json")
    public ResponseEntity<Map<String, Object>> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", "/");
        manifest.put("name", "Home Control");
        manifest.put("short_name", "Home");
        manifest.put("start_url", "/");
        manifest.put("scope", "/");
        manifest.put("display", "standalone");
        manifest.put("background_color", "#14161a");
        manifest.put("theme_color", "#14161a");
        manifest.put("icons", List.of(
                icon("/icons/icon.svg", "any", "image/svg+xml", "any"),
                icon("/icons/icon-192.png", "192x192", "image/png", "any"),
                icon("/icons/icon-512.png", "512x512", "image/png", "any"),
                icon("/icons/maskable-512.png", "512x512", "image/png", "maskable")));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(manifest);
    }

    private static Map<String, Object> icon(String src, String sizes, String type, String purpose) {
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("src", src);
        icon.put("sizes", sizes);
        icon.put("type", type);
        icon.put("purpose", purpose);
        return icon;
    }
}
