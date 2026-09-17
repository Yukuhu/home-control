package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.DeepLinkTestResult;
import dev.andre.homecontrol.playback.DeepLinkTestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.Locale;

/** htmx target of the setup page's "Test deep link" button; blocks for at most the configured timeout. */
@RestController
public class DeepLinkTestController {

    private final DeviceManager devices;
    private final DeepLinkTestService tests;

    public DeepLinkTestController(DeviceManager devices, DeepLinkTestService tests) {
        this.devices = devices;
        this.tests = tests;
    }

    @PostMapping(path = "/setup/devices/{id}/deep-link-test")
    public ResponseEntity<String> test(@PathVariable String id) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        try {
            DeepLinkTestResult result = tests.run(id);
            return fragment(result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-'), result.message());
        } catch (DeviceOfflineException e) {
            // Forgotten between the check above and the test: a result for the page, not an error toast.
            return fragment("failed", e.getMessage());
        } catch (UnsupportedActionException e) {
            return text(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    private static ResponseEntity<String> fragment(String outcome, String message) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body("<p class=\"deep-link-result " + outcome + "\">" + HtmlUtils.htmlEscape(message) + "</p>");
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }
}
