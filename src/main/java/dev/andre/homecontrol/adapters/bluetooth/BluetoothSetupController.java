package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/** The "Bluetooth speakers" section of the setup page: host checks, scan, pair, connect, forget. */
@Controller
@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")
public class BluetoothSetupController {

    private final BluetoothPairingService pairing;
    private final BluetoothHostChecks checks;

    public BluetoothSetupController(BluetoothPairingService pairing, BluetoothHostChecks checks) {
        this.pairing = pairing;
        this.checks = checks;
    }

    @PostMapping("/setup/bluetooth/check")
    public String check() {
        checks.invalidate();
        return "redirect:/setup#bluetooth";
    }

    @PostMapping("/setup/bluetooth/scan")
    public String scan(RedirectAttributes flash) {
        BluetoothScan scan = pairing.scan();
        checks.invalidate();
        if (scan.error() != null) {
            flash.addFlashAttribute("bluetoothError", scan.error());
        } else if (scan.speakers().isEmpty()) {
            flash.addFlashAttribute("bluetoothMessage",
                    "No speakers found. Put the speaker into pairing mode and scan again.");
        } else {
            int count = scan.speakers().size();
            flash.addFlashAttribute("bluetoothMessage", "Found " + count + " device" + (count == 1 ? "" : "s"));
        }
        return "redirect:/setup#bluetooth";
    }

    @PostMapping("/setup/bluetooth/pair")
    public String pair(@RequestParam String address, RedirectAttributes flash) {
        try {
            BluetoothPairing result = pairing.pair(address);
            if (result.warning() != null) {
                flash.addFlashAttribute("bluetoothError", result.warning());
                return "redirect:/setup#bluetooth";
            }
            return "redirect:/?device=" + UriUtils.encodeQueryParam(result.device().id(), StandardCharsets.UTF_8);
        } catch (BluetoothSetupException e) {
            flash.addFlashAttribute("bluetoothError", e.getMessage());
            return "redirect:/setup#bluetooth";
        }
    }

    @PostMapping("/setup/bluetooth/connect")
    public String connect(@RequestParam String id, RedirectAttributes flash) {
        try {
            Device device = pairing.connect(id);
            flash.addFlashAttribute("bluetoothMessage", "Connected " + device.name());
        } catch (BluetoothSetupException e) {
            flash.addFlashAttribute("bluetoothError", e.getMessage());
        }
        return "redirect:/setup#bluetooth";
    }

    @PostMapping("/setup/bluetooth/disconnect")
    public String disconnect(@RequestParam String id, RedirectAttributes flash) {
        try {
            Device device = pairing.disconnect(id);
            flash.addFlashAttribute("bluetoothMessage", "Disconnected " + device.name());
        } catch (BluetoothSetupException e) {
            flash.addFlashAttribute("bluetoothError", e.getMessage());
        }
        return "redirect:/setup#bluetooth";
    }

    @PostMapping("/setup/bluetooth/audio-device")
    public String audioDevice(@RequestParam String id, @RequestParam(required = false) String audioDevice,
                              RedirectAttributes flash) {
        try {
            Device device = pairing.setAudioDevice(id, audioDevice);
            flash.addFlashAttribute("bluetoothMessage", audioDevice == null || audioDevice.isBlank()
                    ? device.name() + " finds its audio output automatically"
                    : device.name() + " plays on " + audioDevice);
        } catch (BluetoothSetupException e) {
            flash.addFlashAttribute("bluetoothError", e.getMessage());
        }
        return "redirect:/setup#bluetooth";
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<String> notFound(DeviceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
    }
}
