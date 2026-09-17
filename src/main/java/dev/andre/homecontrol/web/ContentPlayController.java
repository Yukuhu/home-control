package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Plays a source item on a device. The browser sends only {@code source} and {@code item}; the server
 * re-reads the item, so no playable reference or credential ever comes from or goes to the browser.
 */
@RestController
public class ContentPlayController {

    private final DeviceManager devices;
    private final ContentSources sources;
    private final PlaybackService playback;

    public ContentPlayController(DeviceManager devices, ContentSources sources, PlaybackService playback) {
        this.devices = devices;
        this.sources = sources;
        this.playback = playback;
    }

    @PostMapping(path = "/devices/{id}/play", params = {"source", "item"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> play(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        return text(HttpStatus.OK, playback.play(content.get(), id).describe());
    }

    @GetMapping(path = "/devices/{id}/route", params = {"source", "item"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> route(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        Route route = playback.plan(content.get(), id);
        return route instanceof Route.Unroutable unroutable
                ? text(HttpStatus.UNPROCESSABLE_CONTENT, unroutable.reason())
                : text(HttpStatus.OK, route.describe());
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
