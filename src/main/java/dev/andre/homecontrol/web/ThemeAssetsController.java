package dev.andre.homecontrol.web;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.themes.ThemeCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.util.Map;

/** Public metadata and exact, validated presentation assets; uploads are served only as downloads. */
@Controller
public class ThemeAssetsController {

    private final ThemeCatalog themes;

    public ThemeAssetsController(ThemeCatalog themes) {
        this.themes = themes;
    }

    @GetMapping(path = "/themes/catalog.json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(descriptors());
    }

    @GetMapping(path = "/themes/catalog.js", produces = "text/javascript")
    @ResponseBody
    public ResponseEntity<String> bootstrap() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache())
                .body("globalThis.homeControlThemes=" + Json.MAPPER.writeValueAsString(descriptors()) + ";\n");
    }

    @GetMapping("/themes/packages/**")
    @ResponseBody
    public ResponseEntity<byte[]> asset(HttpServletRequest request) {
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());
        return themes.asset(rawPath)
                .map(asset -> ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .body(asset.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/offline.html")
    public String offline() {
        return "offline";
    }

    private Map<String, Object> descriptors() {
        return Map.of("defaultId", "default", "themes", themes.themes());
    }
}
