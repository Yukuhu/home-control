package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
public class StateController {

    private final DeviceStateBroadcaster broadcaster;
    private final DeviceSessionManager sessions;

    public StateController(DeviceStateBroadcaster broadcaster, DeviceSessionManager sessions) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() throws IOException {
        SseEmitter emitter = broadcaster.subscribe();
        // Send the current state immediately so a new tab is not blank until something changes.
        emitter.send(SseEmitter.event().name("state").data(sessions.state()));
        return emitter;
    }
}
