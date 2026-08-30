package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.protocol.PairingResult;
import dev.andre.shield.storage.StorageException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

@Controller
public class SetupController {

    private final MdnsDiscovery discovery;
    private final PairingService pairing;
    private final DeviceSessionManager sessions;

    public SetupController(MdnsDiscovery discovery, PairingService pairing,
                           DeviceSessionManager sessions) {
        this.discovery = discovery;
        this.pairing = pairing;
        this.sessions = sessions;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        populateSetupModel(model, pairing.inProgress());
        return "setup";
    }

    @PostMapping("/setup/pair")
    public String pair(@RequestParam String host, @RequestParam(required = false) String name,
                       Model model) {
        try {
            pairing.begin(host, name);
            populateSetupModel(model, true);
        } catch (StorageException e) {
            model.addAttribute("error", e.getMessage());
            populateSetupModel(model, false);
        } catch (IOException e) {
            model.addAttribute("error", "Could not reach " + host + ": " + e.getMessage());
            populateSetupModel(model, false);
        }
        return "setup";
    }

    @PostMapping("/setup/code")
    public String code(@RequestParam String code, Model model) {
        PairingResult result;
        try {
            result = pairing.submit(code);
        } catch (StorageException e) {
            model.addAttribute("error", e.getMessage());
            populateSetupModel(model, false);
            return "setup";
        }

        switch (result) {
            case PairingResult.Paired ignored -> {
                return "redirect:/";
            }
            case PairingResult.WrongCode ignored -> model.addAttribute("error",
                    "That code was not accepted. The device will show a new one — start again.");
            case PairingResult.Failed failed -> model.addAttribute("error", failed.reason());
        }

        populateSetupModel(model, false);
        return "setup";
    }

    private void populateSetupModel(Model model, boolean awaitingCode) {
        model.addAttribute("awaitingCode", awaitingCode);
        model.addAttribute("discovered", discovery.devices());
        model.addAttribute("paired", sessions.activeDevice().orElse(null));
    }

    @PostMapping("/setup/forget")
    public String forget(@RequestParam String id) {
        sessions.forget(id);
        return "redirect:/setup";
    }
}
