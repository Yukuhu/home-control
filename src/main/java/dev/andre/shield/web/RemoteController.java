package dev.andre.shield.web;

import dev.andre.shield.apps.AppCatalog;
import dev.andre.shield.apps.AppEntry;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
public class RemoteController {

    private final DeviceSessionManager sessions;
    private final AppCatalog apps;

    public RemoteController(DeviceSessionManager sessions, AppCatalog apps) {
        this.sessions = sessions;
        this.apps = apps;
    }

    @GetMapping("/")
    public String remote(Model model) {
        DeviceState state = sessions.state();
        List<AppEntry> entries = apps.entries();
        model.addAttribute("state", state);
        model.addAttribute("device", sessions.activeDevice().orElse(null));
        model.addAttribute("apps", entries);
        model.addAttribute("currentAppSaved", state.currentApp() != null
                && entries.stream().anyMatch(entry -> entry.appPackage().equals(state.currentApp())));
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

    @PostMapping("/apps/{id}/launch")
    public ResponseEntity<Void> launch(@PathVariable String id) {
        Optional<AppEntry> entry = apps.byId(id);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        session().launchAppLink(entry.get().launchUri());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apps/current")
    public String addCurrentApp(Model model) {
        DeviceState state = sessions.state();
        if (!state.connected() || state.currentApp() == null || state.currentApp().isBlank()) {
            throw new DeviceOfflineException("No current app is available");
        }
        apps.addPackage(state.currentApp());
        model.addAttribute("apps", apps.entries());
        return "remote :: app-list";
    }

    private DeviceSession session() {
        return sessions.active()
                .orElseThrow(() -> new DeviceOfflineException("No device is paired"));
    }

    /** The browser shows a toast; there is nothing to render. */
    @ExceptionHandler(DeviceOfflineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void offline() {
    }
}
