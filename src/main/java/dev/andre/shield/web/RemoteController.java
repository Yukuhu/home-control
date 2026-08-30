package dev.andre.shield.web;

import dev.andre.shield.device.DeviceOfflineException;
import dev.andre.shield.device.DeviceSession;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.DeviceState;
import dev.andre.shield.protocol.RemoteKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Locale;

@Controller
public class RemoteController {

    private final DeviceSessionManager sessions;

    public RemoteController(DeviceSessionManager sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/")
    public String remote(Model model) {
        DeviceState state = sessions.state();
        model.addAttribute("state", state);
        model.addAttribute("device", sessions.activeDevice().orElse(null));
        return "remote";
    }

    @PostMapping("/key/{key}")
    public ResponseEntity<Void> key(@PathVariable String key) {
        RemoteKey remoteKey;
        try {
            remoteKey = RemoteKey.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        session().sendKey(remoteKey);
        return ResponseEntity.noContent().build();
    }

    private DeviceSession session() {
        return sessions.active()
                .orElseThrow(() -> new DeviceOfflineException("No device is paired"));
    }

    @ExceptionHandler(DeviceOfflineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void offline() {
    }
}
