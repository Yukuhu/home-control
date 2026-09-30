package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.playback.DeepLinkTestResult;
import dev.andre.homecontrol.playback.DeepLinkTestService;
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

    private final DeepLinkTestService tests;

    public DeepLinkTestController(DeepLinkTestService tests) {
        this.tests = tests;
    }

    @PostMapping(path = "/setup/devices/{id}/deep-link-test")
    /** An unknown device (404) and one that cannot open app links (422) reach {@link ErrorAdvice}. */
    public ResponseEntity<String> test(@PathVariable String id) {
        try {
            DeepLinkTestResult result = tests.run(id);
            return fragment(result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-'), result.message());
        } catch (DeviceOfflineException e) {
            // An offline device is a test result for the page, not an error toast.
            return fragment("failed", e.getMessage());
        }
    }

    private static ResponseEntity<String> fragment(String outcome, String message) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body("<p class=\"deep-link-result " + outcome + "\">" + HtmlUtils.htmlEscape(message) + "</p>");
    }
}
