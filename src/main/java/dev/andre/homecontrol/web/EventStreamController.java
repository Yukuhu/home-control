package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.security.LoginContext;
import dev.andre.homecontrol.security.LoginService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@code GET /events}: opens a browser tab's {@link EventStream}, starting with a snapshot of every device and rail. */
@RestController
public class EventStreamController {

    private final EventStream stream;
    private final DeviceQueries devices;
    private final RailCache rails;

    public EventStreamController(EventStream stream, DeviceQueries devices, RailCache rails,
                                 ObjectProvider<LoginService> login) {
        this.stream = stream;
        this.devices = devices;
        this.rails = rails;
        LoginService service = login.getIfAvailable();
        if (service != null) {
            // A logout, a password change or a first login ends the streams that may no longer see state.
            service.onChange(stream::revalidate);
        }
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(LoginContext login) {
        // Bound to the session, not the request: the stream outlives the request that opened it.
        return stream.subscribe(login.whileLoggedIn(), this::snapshot);
    }

    /**
     * What a new or reconnecting tab paints before anything changes: every device's state, every rail's summary, so it
     * notices what changed while it was away, and the list of rails, so it notices one that appeared or went.
     */
    private void snapshot(SseEmitter emitter) throws IOException {
        for (Map.Entry<String, DeviceState> entry : devices.states().entrySet()) {
            emitter.send(SseEmitter.event().name("state")
                    .data(new DeviceStateChangedEvent(entry.getKey(), entry.getValue())));
        }
        List<RailSnapshot> snapshots = rails.peek();
        for (RailSnapshot rail : snapshots) {
            emitter.send(SseEmitter.event().name("rail").data(RailEventView.of(rail)));
        }
        List<String> keys = snapshots.stream().map(RailSnapshot::key).toList();
        emitter.send(SseEmitter.event().name("rails").data(Map.of("rails", keys)));
    }
}
