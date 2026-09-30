package dev.andre.homecontrol.web;

import dev.andre.homecontrol.config.SetupSection;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.CodePairing;
import dev.andre.homecontrol.core.CodePairingOutcome;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceSettings;
import dev.andre.homecontrol.core.Hosts;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.playback.DeepLinkTestProperties;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);
    private static final String SETUP_VIEW = "setup";
    private static final String ERROR_ATTRIBUTE = "error";

    private final ObjectProvider<CodePairing> codePairings;
    private final DeviceQueries devices;
    private final DeviceEnrollment enrollment;
    private final DeviceSettings deviceSettings;
    private final List<PromptPairing> promptPairings;

    private final Duration deepLinkTestTimeout;
    private final ObjectProvider<SetupSection> sections;
    private final ObjectProvider<LoginService> login;

    /**
     * {@code codePairings}: Android TV's, absent when that module is switched off.
     * {@code promptPairings}: one per enabled smart-TV module; empty when none is.
     * {@code deepLinkTest}: its timeout is shown next to the "Test deep link" button.
     * {@code sections}: one per enabled module that has something to set up.
     */
    // The page's device half (pairing, enrollment, settings, the deep-link test), its sections and its account.
    @SuppressWarnings("java:S107")
    public SetupController(ObjectProvider<CodePairing> codePairings, DeviceQueries devices,
                           DeviceEnrollment enrollment, DeviceSettings deviceSettings,
                           List<PromptPairing> promptPairings, DeepLinkTestProperties deepLinkTest,
                           ObjectProvider<SetupSection> sections, ObjectProvider<LoginService> login) {
        this.codePairings = codePairings;
        this.devices = devices;
        this.enrollment = enrollment;
        this.deviceSettings = deviceSettings;
        this.promptPairings = List.copyOf(promptPairings);
        this.deepLinkTestTimeout = deepLinkTest.timeout();
        this.sections = sections;
        this.login = login;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        populateSetupModel(model, codePairing().map(CodePairing::inProgress).orElse(false));
        return SETUP_VIEW;
    }

    @PostMapping("/setup/pair")
    public String pair(@RequestParam String host, @RequestParam(required = false) String name,
                       Model model) {
        CodePairing pairing = requireCodePairing();
        String address = host.trim();
        if (!Hosts.isValid(address)) {
            return notAHost(model);
        }
        try {
            pairing.begin(address, name);
            populateSetupModel(model, true);
        } catch (StorageException e) {
            model.addAttribute(ERROR_ATTRIBUTE, e.getMessage());
            populateSetupModel(model, false);
        } catch (IOException e) {
            log.warn("Could not begin Android TV pairing with {}", address, e);
            model.addAttribute(ERROR_ATTRIBUTE, "Could not connect to " + address + ": " + e.getMessage());
            populateSetupModel(model, false);
        }
        return SETUP_VIEW;
    }

    @PostMapping("/setup/code")
    public String code(@RequestParam String code, Model model) {
        CodePairing pairing = requireCodePairing();
        CodePairingOutcome result;
        try {
            result = pairing.submit(code);
        } catch (StorageException e) {
            model.addAttribute(ERROR_ATTRIBUTE, e.getMessage());
            populateSetupModel(model, false);
            return SETUP_VIEW;
        }

        switch (result) {
            case CodePairingOutcome.Paired() -> {
                return "redirect:/";
            }
            case CodePairingOutcome.WrongCode() -> model.addAttribute(ERROR_ATTRIBUTE,
                    "That code was not accepted. The device will show a new one — start again.");
            case CodePairingOutcome.Failed(var reason) -> model.addAttribute(ERROR_ATTRIBUTE, reason);
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
        String address = host.trim();
        if (!Hosts.isValid(address)) {
            return notAHost(model);
        }
        Optional<PromptPairing> chosen = promptPairings.stream()
                .filter(candidate -> candidate.adapterId().equals(adapter)).findFirst();
        if (chosen.isEmpty()) {
            model.addAttribute(ERROR_ATTRIBUTE, "Unknown device type " + adapter);
            populateSetupModel(model, false);
            return SETUP_VIEW;
        }
        switch (chosen.get().pair(address, name)) {
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
        // An unknown device is a DeviceNotFoundException from the settings, a 404 through ErrorAdvice.
        return refusable(model, () -> deviceSettings.setWakeOnLanMac(id, mac));
    }

    private Optional<CodePairing> codePairing() {
        return Optional.ofNullable(codePairings.getIfAvailable());
    }

    private CodePairing requireCodePairing() {
        return codePairing().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Android TV is switched off"));
    }

    private void populateSetupModel(Model model, boolean awaitingCode) {
        model.addAttribute("codePairing", codePairing().isPresent());
        model.addAttribute("awaitingCode", awaitingCode);
        model.addAttribute("discovered", enrollment.pairable());
        model.addAttribute("addable", enrollment.addable());
        List<Device> paired = devices.devices();
        model.addAttribute("paired", paired);
        model.addAttribute("promptPairings", promptPairings);
        model.addAttribute("promptAdapterIds", promptPairings.stream()
                .map(PromptPairing::adapterId).collect(Collectors.toSet()));
        model.addAttribute("promptInstructions", promptPairings.stream()
                .collect(Collectors.toMap(PromptPairing::adapterId, PromptPairing::instructions, (first, second) -> first)));
        Map<String, String> wakeMacs = new LinkedHashMap<>();
        paired.stream().filter(device -> deviceSettings.wakesOnLan(device.id()))
                .forEach(device -> wakeMacs.put(device.id(), deviceSettings.wakeOnLanMac(device.id()).orElse("")));
        model.addAttribute("wakeMacs", wakeMacs);
        model.addAttribute("deepLinkTestable", paired.stream()
                .map(Device::id)
                .filter(id -> devices.capabilities(id).contains(Capability.APP_LINK))
                .collect(Collectors.toSet()));
        model.addAttribute("deepLinkTestSeconds", Math.max(1, (deepLinkTestTimeout.toMillis() + 999) / 1000));
        populateSections(model);
        LoginService loginService = login.getIfAvailable();
        model.addAttribute("loginRequired", loginService != null && loginService.loginRequired());
        model.addAttribute("connectedAccounts", loginService == null ? List.of() : loginService.connectedAccounts());
    }

    /** Each module's section under its id, and the sections by group in their order; one with nothing to show is left out. */
    private void populateSections(Model model) {
        URI baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUri();
        Map<SetupSection.Group, List<SetupSection>> shown = new EnumMap<>(SetupSection.Group.class);
        sections.orderedStream().sorted(Comparator.comparingInt(SetupSection::order)).forEach(section -> {
            Object view = section.view(baseUrl);
            if (view != null) {
                model.addAttribute(section.id(), view);
                shown.computeIfAbsent(section.group(), group -> new ArrayList<>()).add(section);
            }
        });
        model.addAttribute("deviceSections", shown.getOrDefault(SetupSection.Group.DEVICES, List.of()));
        model.addAttribute("sourceSections", shown.getOrDefault(SetupSection.Group.CONTENT_SOURCES, List.of()));
    }

    @PostMapping("/setup/forget")
    public String forget(@RequestParam String id) {
        enrollment.forget(id);
        return "redirect:/setup";
    }

    @PostMapping("/setup/add")
    public String add(@RequestParam String adapter, @RequestParam String host, @RequestParam int port, Model model) {
        return refusable(model, () -> enrollment.addDiscovered(adapter, host, port));
    }

    @PostMapping("/setup/merge")
    public String merge(@RequestParam String target, @RequestParam String source, Model model) {
        return refusable(model, () -> enrollment.merge(target, source));
    }

    @PostMapping("/setup/split")
    public String split(@RequestParam String id, @RequestParam String adapter, Model model) {
        return refusable(model, () -> enrollment.split(id, adapter));
    }

    /** Refused before anything connects: adapters build the address of a device from its host. */
    private String notAHost(Model model) {
        model.addAttribute(ERROR_ATTRIBUTE,
                "Enter the device's address as a host name or an IP address, such as 192.168.1.50");
        populateSetupModel(model, false);
        return SETUP_VIEW;
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
