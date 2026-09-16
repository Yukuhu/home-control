package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The device strip plus the selected device's drawer: remote keys and/or Cast controls (spec §6.1). Rails arrive in sub-project D. */
@Controller
public class DashboardController {

    private final DeviceManager devices;

    public DashboardController(DeviceManager devices) {
        this.devices = devices;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "device", required = false) String deviceId, Model model) {
        List<Device> all = devices.devices();
        if (all.isEmpty()) {
            return "redirect:/setup";
        }
        Device selected = Optional.ofNullable(deviceId).flatMap(devices::device)
                .or(devices::defaultDevice)
                .orElse(all.getFirst());
        // Built from the same list the strip iterates, so every chip has a state even if a
        // device is paired or forgotten between the two reads.
        Map<String, DeviceState> states = new LinkedHashMap<>();
        all.forEach(device -> states.put(device.id(), devices.state(device.id())));
        model.addAttribute("devices", all);
        model.addAttribute("states", states);
        model.addAttribute("selected", selected);
        model.addAttribute("selectedState", devices.state(selected.id()));
        Set<Capability> capabilities = devices.capabilities(selected.id());
        model.addAttribute("remoteKeys", capabilities.contains(Capability.REMOTE_KEYS));
        model.addAttribute("castControls", capabilities.contains(Capability.CAST_RECEIVER));
        model.addAttribute("canOpenLinks",
                capabilities.contains(Capability.APP_LINK) || capabilities.contains(Capability.CAST_RECEIVER));
        return "dashboard";
    }

    @GetMapping("/remote/{id}")
    public String remote(@PathVariable String id) {
        // Built rather than interpolated so a device id containing "&" or "=" cannot smuggle
        // an extra query parameter into the redirect.
        String target = UriComponentsBuilder.fromPath("/").queryParam("device", id).encode().build().toUriString();
        return "redirect:" + target;
    }
}
