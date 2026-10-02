package dev.andre.homecontrol.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the container's HEALTHCHECK: "ok" when the server serves requests. Open without a login, it tells nothing
 * beyond that, and it touches no session, store or device.
 */
@RestController
public class HealthController {

    @GetMapping(path = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }
}
