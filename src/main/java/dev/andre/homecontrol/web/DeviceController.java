package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Commands addressed to one device. Every failure is a plain-text reason the UI can toast.
 * Whether the device exists is decided once, by {@link DeviceManager}/{@link PlaybackService},
 * which throw {@link DeviceNotFoundException} (404) for an unknown id.
 */
@RestController
public class DeviceController {

    private final DeviceManager devices;
    private final PlaybackService playback;

    public DeviceController(DeviceManager devices, PlaybackService playback) {
        this.devices = devices;
        this.playback = playback;
    }

    @PostMapping("/devices/{id}/key/{key}")
    public ResponseEntity<String> key(@PathVariable String id, @PathVariable String key) {
        RemoteKey remoteKey;
        try {
            remoteKey = RemoteKey.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return text(HttpStatus.BAD_REQUEST, "Unknown key " + key);
        }
        devices.execute(id, new Action.PressKey(remoteKey));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/devices/{id}/play", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> play(@PathVariable String id, @RequestParam String uri) {
        ContentItem item;
        try {
            item = AppLinks.fromUrl(uri);
        } catch (IllegalArgumentException e) {
            // Only a bad link is a 400; any other IllegalArgumentException is a bug, not user input.
            return text(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        Route route = playback.play(item, id);
        return text(HttpStatus.OK, route.describe());
    }

    @PostMapping("/devices/{id}/volume")
    public ResponseEntity<String> volume(@PathVariable String id, @RequestParam int level) {
        Action.SetVolume action;
        try {
            action = new Action.SetVolume(level);
        } catch (IllegalArgumentException e) {
            // Only an out-of-range level is a 400; the device's own failures keep their status.
            return text(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return command(id, action);
    }

    @PostMapping("/devices/{id}/mute")
    public ResponseEntity<String> mute(@PathVariable String id, @RequestParam boolean muted) {
        return command(id, new Action.Mute(muted));
    }

    @PostMapping("/devices/{id}/stop")
    public ResponseEntity<String> stop(@PathVariable String id) {
        return command(id, new Action.Stop());
    }

    /** Volume and stop go straight to the adapters, not through the planner (spec §5.3). */
    private ResponseEntity<String> command(String id, Action action) {
        devices.execute(id, action);
        return ResponseEntity.noContent().build();
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

    @ExceptionHandler(ActionFailedException.class)
    public ResponseEntity<String> failed(ActionFailedException e) {
        return text(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }
}
