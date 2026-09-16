package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

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
            // Send the current state immediately so a new tab is not blank until something
            // changes. Interim (Task 6): the default device only, and nothing at all when
            // there is none to report — Task 7 sends every registered device.
            Device device = sessions.defaultDevice().orElse(null);
            if (device != null) {
                emitter.send(SseEmitter.event().name("state")
                        .data(new DeviceStateChangedEvent(device.id(), sessions.state(device.id()))));
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
