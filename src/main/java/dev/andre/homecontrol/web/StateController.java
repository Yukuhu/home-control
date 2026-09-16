package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
public class StateController {

    private final DeviceStateBroadcaster broadcaster;
    private final DeviceManager sessions;

    public StateController(DeviceStateBroadcaster broadcaster, DeviceManager sessions) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() throws IOException {
        SseEmitter emitter = broadcaster.subscribe();
        try {
            // One snapshot per device so a new tab paints every chip before anything changes.
            for (Map.Entry<String, DeviceState> entry : sessions.states().entrySet()) {
                emitter.send(SseEmitter.event().name("state")
                        .data(new DeviceStateChangedEvent(entry.getKey(), entry.getValue())));
            }
        } catch (IOException e) {
            // The emitter never reached Spring, so its onCompletion/onTimeout/onError
            // will never fire; undo the subscribe ourselves or it leaks forever.
            broadcaster.unsubscribe(emitter);
            throw e;
        }
        return emitter;
    }
}
