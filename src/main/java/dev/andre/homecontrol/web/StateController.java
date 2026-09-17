package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;

@RestController
public class StateController {

    private final DeviceStateBroadcaster broadcaster;
    private final DeviceManager sessions;
    private final RailCache rails;
    private final LoginService login;

    public StateController(DeviceStateBroadcaster broadcaster, DeviceManager sessions, RailCache rails,
                           ObjectProvider<LoginService> login) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
        this.rails = rails;
        this.login = login.getIfAvailable();
        if (this.login != null) {
            // A logout, a password change or a first login ends the streams that may no longer see state.
            this.login.onChange(broadcaster::revalidate);
        }
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletRequest request) throws IOException {
        SseEmitter emitter = broadcaster.subscribe(stillAllowed(request));
        try {
            // One snapshot per device so a new tab paints every chip before anything changes.
            for (Map.Entry<String, DeviceState> entry : sessions.states().entrySet()) {
                emitter.send(SseEmitter.event().name("state")
                        .data(new DeviceStateChangedEvent(entry.getKey(), entry.getValue())));
            }
            // Rail summaries let a reconnecting tab notice what changed while it was away.
            for (RailSnapshot rail : rails.peek()) {
                emitter.send(SseEmitter.event().name("rail").data(RailEventView.of(rail)));
            }
        } catch (IOException e) {
            // The emitter never reached Spring, so its onCompletion/onTimeout/onError
            // will never fire; undo the subscribe ourselves or it leaks forever.
            broadcaster.unsubscribe(emitter);
            throw e;
        }
        return emitter;
    }

    /** Bound to the session, not the request: the stream outlives the request that opened it. */
    private BooleanSupplier stillAllowed(HttpServletRequest request) {
        if (login == null) {
            return () -> true;
        }
        HttpSession session = request.getSession(false);
        return () -> login.isAuthenticated(session);
    }
}
