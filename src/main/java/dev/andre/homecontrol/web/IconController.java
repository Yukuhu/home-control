package dev.andre.homecontrol.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** Renders the PWA's raster icons on demand and caches the bytes; there is no artwork on disk. */
@Controller
public class IconController {

    private record Spec(int size, IconRenderer.Shape shape) {
    }

    private static final Map<String, Spec> ICONS = Map.of(
            "icon-192", new Spec(192, IconRenderer.Shape.ROUNDED),
            "icon-512", new Spec(512, IconRenderer.Shape.ROUNDED),
            "maskable-512", new Spec(512, IconRenderer.Shape.MASKABLE),
            "apple-touch-icon", new Spec(180, IconRenderer.Shape.FULL_BLEED));

    private final BiFunction<Integer, IconRenderer.Shape, byte[]> renderer;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public IconController() {
        this(IconRenderer::png);
    }

    IconController(BiFunction<Integer, IconRenderer.Shape, byte[]> renderer) {
        this.renderer = renderer;
    }

    @GetMapping("/icons/{name}.png")
    public ResponseEntity<byte[]> icon(@PathVariable String name) {
        Spec spec = ICONS.get(name);
        if (spec == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = cache.computeIfAbsent(name, ignored -> renderer.apply(spec.size(), spec.shape()));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(bytes);
    }
}
