package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.device.DeviceManager;
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

    private final DeviceManager sessions;

    public RemoteController(DeviceManager sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/")
    public String remote(Model model) {
        Device device = sessions.defaultDevice().orElse(null);
        DeviceState state = device == null ? DeviceState.initial() : sessions.state(device.id());
        model.addAttribute("state", state);
        model.addAttribute("device", device);
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
        sessions.execute(defaultId(), new Action.PressKey(remoteKey));
        return ResponseEntity.noContent().build();
    }

    private String defaultId() {
        return sessions.defaultDevice().map(Device::id)
                .orElseThrow(() -> new DeviceOfflineException("No device is paired"));
    }

    @ExceptionHandler(DeviceOfflineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void offline() {
    }
}
