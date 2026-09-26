package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinOffers;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlayAttempt;
import dev.andre.homecontrol.playback.PlaybackService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plays a source item on a device. The browser sends only {@code source} and {@code item}; the server
 * re-reads the item, so no playable reference or credential ever comes from or goes to the browser.
 */
@RestController
public class ContentPlayController {

    private static final String NO_DEVICE_PREFIX = "No device with id ";

    private static final int MAX_SKIP = 8;
    private static final int MAX_SKIP_LENGTH = 64;

    private final DeviceManager devices;
    private final ContentSources sources;
    private final PlaybackService playback;
    private final ObjectProvider<PinnedLinks> pinnedLinks;

    public ContentPlayController(DeviceManager devices, ContentSources sources, PlaybackService playback,
                                 ObjectProvider<PinnedLinks> pinnedLinks) {
        this.devices = devices;
        this.sources = sources;
        this.playback = playback;
        this.pinnedLinks = pinnedLinks;
    }

    @PostMapping(path = "/devices/{id}/play", params = {"source", "item"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> play(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, NO_DEVICE_PREFIX + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        return text(HttpStatus.OK, playback.play(content.get(), id).describe(content.get().kind()));
    }

    @GetMapping(path = "/devices/{id}/route", params = {"source", "item"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> route(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, NO_DEVICE_PREFIX + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        Route route = playback.plan(content.get(), id);
        return route instanceof Route.Unroutable(var reason)
                ? text(HttpStatus.UNPROCESSABLE_CONTENT, reason)
                : text(HttpStatus.OK, route.describe(content.get().kind()));
    }

    @GetMapping(path = "/devices/{id}/route-preview", params = {"source", "item"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, NO_DEVICE_PREFIX + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        PinOfferView pin = pinnedLinks.getIfAvailable() == null ? null
                : PinOffers.offer(content.get()).map(PinOfferView::of).orElse(null);
        return ResponseEntity.ok(RoutePreviewView.of(playback.preview(content.get(), id), pin, content.get().kind()));
    }

    @PostMapping(path = "/devices/{id}/play-attempt", params = {"source", "item"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> attempt(@PathVariable String id, @RequestParam String source, @RequestParam String item,
                                     @RequestParam(name = "skip", required = false) List<String> skip) {
        List<String> skips = skip == null ? List.of() : skip.stream().filter(s -> !s.isBlank()).toList();
        if (skips.size() > MAX_SKIP || skips.stream().anyMatch(s -> s.length() > MAX_SKIP_LENGTH)) {
            return text(HttpStatus.BAD_REQUEST, "Too many or too long route keys to skip");
        }
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, NO_DEVICE_PREFIX + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        ContentKind kind = content.get().kind();
        return switch (playback.attempt(content.get(), id, Set.copyOf(skips))) {
            case PlayAttempt.Played(var device, var route, var remaining) -> ResponseEntity.ok(new PlayResultView(true, id, device.name(),
                    RouteView.of(route, kind), RouteView.of(first(remaining), kind),
                    route.describe(kind)));
            case PlayAttempt.Failed(var failedDevice, var failedRoute, var failedRemaining, var cause) -> ResponseEntity.status(statusOf(cause)).body(new PlayResultView(false, id,
                    failedDevice.name(), RouteView.of(failedRoute, kind), RouteView.of(first(failedRemaining), kind),
                    cause.getMessage()));
            case PlayAttempt.Unroutable(var unroutableDevice, var unroutableReason) -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
                    new PlayResultView(false, id, unroutableDevice.name(), null, null,
                            unroutableDevice.name() + ": " + unroutableReason));
        };
    }

    private static Route first(List<Route> routes) {
        return routes.isEmpty() ? null : routes.getFirst();
    }

    private static HttpStatus statusOf(RuntimeException cause) {
        return switch (cause) {
            case DeviceOfflineException _ -> HttpStatus.CONFLICT;
            case UnsupportedActionException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private Optional<ContentItem> find(String source, String item) {
        return sources.find(source).flatMap(found -> found.item(item));
    }

    private ResponseEntity<String> notFound(String source) {
        return sources.find(source).isEmpty()
                ? text(HttpStatus.NOT_FOUND, "No content source " + source)
                : text(HttpStatus.NOT_FOUND, "No such item");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> bad(IllegalArgumentException e) {
        return text(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<String> notFound(DeviceNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeviceOfflineException.class)
    public ResponseEntity<String> offline(DeviceOfflineException e) {
        return text(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({UnsupportedActionException.class, UnroutableException.class})
    public ResponseEntity<String> cannot(RuntimeException e) {
        return text(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler({ActionFailedException.class, ContentSourceException.class})
    public ResponseEntity<String> failed(RuntimeException e) {
        return text(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }
}
