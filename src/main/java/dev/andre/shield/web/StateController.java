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
        try {
            // Send the current state immediately so a new tab is not blank until something changes.
            emitter.send(SseEmitter.event().name("state").data(sessions.state()));
        } catch (IOException e) {
            // The emitter never reached Spring, so its onCompletion/onTimeout/onError
            // will never fire; undo the subscribe ourselves or it leaks forever.
            broadcaster.unsubscribe(emitter);
            throw e;
        }
        return emitter;
    }
}
