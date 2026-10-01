package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.support.PairingKeys;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenDeviceInfo;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenRemoteConnection;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenRest;
import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceSecrets;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/** "Allow Home Control?" on the TV: connect without a token and keep the token the TV hands out. */
public class TizenPairing implements PromptPairing {

    private final TizenProperties properties;
    private final DeviceQueries devices;
    private final DeviceEnrollment enrollment;
    private final PairingKeys keys;
    private final HttpClient http;
    private final TizenRest rest;

    public TizenPairing(TizenProperties properties, DeviceQueries devices, DeviceEnrollment enrollment,
                        DeviceSecrets secrets) {
        this.properties = properties;
        this.devices = devices;
        this.enrollment = enrollment;
        this.keys = TizenSettings.keys(secrets);
        this.http = InsecureTls.httpClient(properties.connectTimeout());
        this.rest = new TizenRest(http, properties.protocol());
    }

    @Override
    public String adapterId() {
        return TizenAdapter.ADAPTER_ID;
    }

    @Override
    public String displayName() {
        return "Samsung TV";
    }

    @Override
    public String instructions() {
        return "The TV asks whether to allow \"" + properties.clientName() + "\". Choose Allow with the TV remote within "
                + properties.pairingTimeout().toSeconds() + " seconds.";
    }

    @Override
    public PromptPairingResult pair(String host, String name) {
        try (TizenRemoteConnection connection = TizenRemoteConnection.open(http, host, properties.protocol(), null, reason -> { })) {
            return switch (connection.awaitAuthorization(properties.pairingTimeout())) {
                case CONNECTED -> {
                    Map<String, String> settings = new LinkedHashMap<>();
                    settings.put(TizenSettings.PAIRED_KEY, "true");
                    connection.token().ifPresent(token ->
                            settings.put(TizenSettings.KEY_REF, keys.storePaired(devices.devices(), host, token)));
                    String deviceName = name != null && !name.isBlank() ? name.trim()
                            : rest.deviceInfo(host).map(TizenDeviceInfo::name).filter(n -> !n.isBlank()).orElse("Samsung TV");
                    yield new PromptPairingResult.Paired(
                            enrollment.attach(host, deviceName, DeviceKind.TIZEN, TizenAdapter.ADAPTER_ID, settings));
                }
                case UNAUTHORIZED -> new PromptPairingResult.Declined("The TV declined the connection request");
                case NO_ANSWER -> new PromptPairingResult.Failed("Nobody allowed the connection on the TV within "
                        + properties.pairingTimeout().toSeconds() + " seconds; try again");
            };
        } catch (IOException e) {
            return new PromptPairingResult.Failed("Could not reach a Samsung TV at " + host + ": " + e.getMessage());
        }
    }
}
