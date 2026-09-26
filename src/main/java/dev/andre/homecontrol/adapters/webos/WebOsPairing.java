package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * "Accept the request on your TV": registers without a key and stores the key the TV hands out.
 * The MAC address is not collected here; the session learns it right after connecting.
 */
public class WebOsPairing implements PromptPairing {

    private static final String DEFAULT_DEVICE_NAME = "LG webOS TV";

    private final WebOsProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceManager devices;
    private final HttpClient http;

    public WebOsPairing(WebOsProperties properties, SsdpDiscovery ssdp, DeviceManager devices) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.devices = devices;
        this.http = InsecureTls.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
    }

    @Override
    public String adapterId() {
        return WebOsAdapter.ADAPTER_ID;
    }

    @Override
    public String displayName() {
        return DEFAULT_DEVICE_NAME;
    }

    @Override
    public String instructions() {
        return "The TV asks whether to allow Home Control. Accept with the TV remote within "
                + properties.pairingTimeoutSeconds() + " seconds.";
    }

    @Override
    public PromptPairingResult pair(String host, String name) {
        SsapConnection connection = null;
        try {
            connection = SsapConnection.open(http, host, properties, reason -> { });
            String key = connection.register(null, Duration.ofSeconds(properties.pairingTimeoutSeconds()));
            Device device = devices.attach(host, deviceName(connection, host, name), DeviceKind.WEBOS,
                    WebOsAdapter.ADAPTER_ID, Map.of(WebOsSettings.CLIENT_KEY, key));
            return new PromptPairingResult.Paired(device);
        } catch (SsapPairingException e) {
            return switch (e.reason()) {
                case DECLINED -> new PromptPairingResult.Declined(
                        "The TV declined the pairing request (" + e.getMessage() + ")");
                case TIMED_OUT, KEY_REJECTED -> new PromptPairingResult.Failed(e.getMessage() + "; try again");
            };
        } catch (IOException e) {
            return new PromptPairingResult.Failed("Could not reach an LG webOS TV at " + host + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /** The user's name, else the SSDP name for that host, else "LG " + model, else a generic name. */
    private String deviceName(SsapConnection connection, String host, String name) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return ssdp.services(WebOsAdapter.SEARCH_TARGET).stream()
                .filter(service -> service.address().equalsIgnoreCase(host))
                .map(WebOsAdapter::name)
                .findFirst()
                .orElseGet(() -> {
                    try {
                        String model = connection.request(SsapUris.SYSTEM_INFO, SsapMessages.empty())
                                .path("modelName").asString("");
                        return model.isEmpty() ? DEFAULT_DEVICE_NAME : "LG " + model;
                    } catch (IOException _) {
                        return DEFAULT_DEVICE_NAME;
                    }
                });
    }
}
