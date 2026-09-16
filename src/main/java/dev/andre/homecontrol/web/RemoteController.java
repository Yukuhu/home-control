package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.device.DeviceSessionManager;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.RemoteKey;
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
        session().execute(new Action.PressKey(remoteKey));
        return ResponseEntity.noContent().build();
    }

    private DeviceHandle session() {
        return sessions.active()
                .orElseThrow(() -> new DeviceOfflineException("No device is paired"));
    }

    @ExceptionHandler(DeviceOfflineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void offline() {
    }
}
