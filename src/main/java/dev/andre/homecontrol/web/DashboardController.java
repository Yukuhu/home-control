package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.content.ContentSources;
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

/** The device strip, rails and the selected device's drawer: remote keys and/or Cast controls (spec §6.1). */
@Controller
public class DashboardController {

    private final DeviceManager devices;
    private final RailCache rails;
    private final ContentSources contentSources;

    public DashboardController(DeviceManager devices, RailCache rails, ContentSources contentSources) {
        this.devices = devices;
        this.rails = rails;
        this.contentSources = contentSources;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "device", required = false) String deviceId,
                            @RequestParam(name = "remote", required = false) String remote, Model model) {
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
        model.addAttribute("rendererControls",
                capabilities.contains(Capability.MEDIA_RENDERER) || capabilities.contains(Capability.LOCAL_AUDIO_SINK));
        model.addAttribute("localAudio", capabilities.contains(Capability.LOCAL_AUDIO_SINK));
        model.addAttribute("speakerTopology", devices.speakerTopology(selected.id()).orElse(null));
        model.addAttribute("inputs", devices.inputs(selected.id()));
        model.addAttribute("canOpenLinks",
                capabilities.contains(Capability.APP_LINK) || capabilities.contains(Capability.CAST_RECEIVER)
                        || capabilities.contains(Capability.MEDIA_RENDERER) || capabilities.contains(Capability.LOCAL_AUDIO_SINK));
        model.addAttribute("rails", rails.snapshots().stream().map(s -> RailView.of(s, contentSources)).toList());
        model.addAttribute("hasSources", !contentSources.all().isEmpty());
        model.addAttribute("remoteOpen", "open".equals(remote));
        return "dashboard";
    }

    @GetMapping("/remote/{id}")
    public String remote(@PathVariable String id) {
        // Built rather than interpolated so a device id containing "&" or "=" cannot smuggle
        // an extra query parameter into the redirect.
        String target = UriComponentsBuilder.fromPath("/").queryParam("device", id)
                .queryParam("remote", "open").encode().build().toUriString();
        return "redirect:" + target;
    }
}
