package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.adapters.androidtv.PairingOutcome;
import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);
    private static final String SETUP_VIEW = "setup";
    private static final String ERROR_ATTRIBUTE = "error";

    private final PairingService pairing;
    private final DeviceManager devices;
    private final List<PromptPairing> promptPairings;

    private final Duration deepLinkTestTimeout;

    /**
     * {@code promptPairings}: one per enabled smart-TV module; empty when none is.
     * {@code deepLinkTestTimeout}: shown next to the "Test deep link" button.
     */
    public SetupController(PairingService pairing, DeviceManager devices, List<PromptPairing> promptPairings,
                           @Value("${home-control.deep-link-test.timeout:10s}") Duration deepLinkTestTimeout) {
        this.pairing = pairing;
        this.devices = devices;
        this.promptPairings = List.copyOf(promptPairings);
        this.deepLinkTestTimeout = deepLinkTestTimeout;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        populateSetupModel(model, pairing.inProgress());
        return SETUP_VIEW;
    }

    @PostMapping("/setup/pair")
    public String pair(@RequestParam String host, @RequestParam(required = false) String name,
                       Model model) {
        try {
            pairing.begin(host, name);
            populateSetupModel(model, true);
        } catch (StorageException e) {
            model.addAttribute(ERROR_ATTRIBUTE, e.getMessage());
            populateSetupModel(model, false);
        } catch (IOException e) {
            log.warn("Could not begin Android TV pairing with {}", host, e);
            model.addAttribute(ERROR_ATTRIBUTE, "Could not connect to " + host + ": " + e.getMessage());
            populateSetupModel(model, false);
        }
        return SETUP_VIEW;
    }

    @PostMapping("/setup/code")
    public String code(@RequestParam String code, Model model) {
        PairingOutcome result;
        try {
            result = pairing.submit(code);
        } catch (StorageException e) {
            model.addAttribute(ERROR_ATTRIBUTE, e.getMessage());
            populateSetupModel(model, false);
            return SETUP_VIEW;
        }

        switch (result) {
            case PairingOutcome.Paired() -> {
                return "redirect:/";
            }
            case PairingOutcome.WrongCode() -> model.addAttribute(ERROR_ATTRIBUTE,
                    "That code was not accepted. The device will show a new one — start again.");
            case PairingOutcome.Failed(var reason) -> model.addAttribute(ERROR_ATTRIBUTE, reason);
        }

        populateSetupModel(model, false);
        return SETUP_VIEW;
    }

    /**
     * Pairing by accepting a prompt on the TV. Blocks this request until the TV answers or the
     * module's pairing timeout elapses; the page says so next to the form.
     */
    @PostMapping("/setup/prompt-pair")
    public String promptPair(@RequestParam String adapter, @RequestParam String host,
                             @RequestParam(required = false) String name, Model model) {
        Optional<PromptPairing> chosen = promptPairings.stream()
                .filter(candidate -> candidate.adapterId().equals(adapter)).findFirst();
        if (chosen.isEmpty()) {
            model.addAttribute(ERROR_ATTRIBUTE, "Unknown device type " + adapter);
            populateSetupModel(model, false);
            return SETUP_VIEW;
        }
        switch (chosen.get().pair(host.trim(), name)) {
            case PromptPairingResult.Paired(var device) -> {
                return "redirect:" + UriComponentsBuilder.fromPath("/").queryParam("device", device.id())
                        .encode().build().toUriString();
            }
            case PromptPairingResult.Declined(var reason) -> model.addAttribute(ERROR_ATTRIBUTE, reason);
            case PromptPairingResult.Failed(var reason) -> model.addAttribute(ERROR_ATTRIBUTE, reason);
        }
        populateSetupModel(model, false);
        return SETUP_VIEW;
    }

    /** A hand-entered Wake-on-LAN MAC address; blank clears it so the TV's own report is learned again. */
    @PostMapping("/setup/devices/{id}/mac")
    public String wakeOnLanMac(@PathVariable String id, @RequestParam(required = false) String mac, Model model) {
        if (devices.device(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        return refusable(model, () -> devices.setWakeOnLanMac(id, mac));
    }

    private void populateSetupModel(Model model, boolean awaitingCode) {
        model.addAttribute("awaitingCode", awaitingCode);
        model.addAttribute("discovered", devices.pairable());
        model.addAttribute("addable", devices.addable());
        List<Device> paired = devices.devices();
        model.addAttribute("paired", paired);
        model.addAttribute("promptPairings", promptPairings);
        model.addAttribute("promptAdapterIds", promptPairings.stream()
                .map(PromptPairing::adapterId).collect(Collectors.toSet()));
        model.addAttribute("promptInstructions", promptPairings.stream()
                .collect(Collectors.toMap(PromptPairing::adapterId, PromptPairing::instructions, (first, second) -> first)));
        Map<String, String> wakeMacs = new LinkedHashMap<>();
        paired.stream().filter(device -> devices.wakesOnLan(device.id()))
                .forEach(device -> wakeMacs.put(device.id(), devices.wakeOnLanMac(device.id()).orElse("")));
        model.addAttribute("wakeMacs", wakeMacs);
        model.addAttribute("deepLinkTestable", paired.stream()
                .map(Device::id)
                .filter(id -> devices.capabilities(id).contains(Capability.APP_LINK))
                .collect(Collectors.toSet()));
        model.addAttribute("deepLinkTestSeconds", Math.max(1, (deepLinkTestTimeout.toMillis() + 999) / 1000));
    }

    @PostMapping("/setup/forget")
    public String forget(@RequestParam String id) {
        devices.forget(id);
        return "redirect:/setup";
    }

    @PostMapping("/setup/add")
    public String add(@RequestParam String adapter, @RequestParam String host, @RequestParam int port, Model model) {
        return refusable(model, () -> devices.addDiscovered(adapter, host, port));
    }

    @PostMapping("/setup/merge")
    public String merge(@RequestParam String target, @RequestParam String source, Model model) {
        return refusable(model, () -> devices.merge(target, source));
    }

    @PostMapping("/setup/split")
    public String split(@RequestParam String id, @RequestParam String adapter, Model model) {
        return refusable(model, () -> devices.split(id, adapter));
    }

    /** A refusal is a sentence for the user, shown on the page; success goes back to setup. */
    private String refusable(Model model, Runnable change) {
        try {
            change.run();
            return "redirect:/setup";
        } catch (IllegalArgumentException e) {
            model.addAttribute(ERROR_ATTRIBUTE, e.getMessage());
            populateSetupModel(model, false);
            return SETUP_VIEW;
        }
    }
}
