package dev.andre.homecontrol.adapters.tizen.protocol;

import dev.andre.homecontrol.adapters.net.DeviceUris;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The TV's REST API on 8001. Every failure is "no answer": the TV may be off or the model may lack the endpoint. The
 * request timeout bounds the whole answer, so a TV that stalls mid-body cannot hold the session.
 */
public final class TizenRest {

    /** The device info is a few KiB; anything past this is refused unread. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final HttpClient http;
    private final TizenOptions options;

    public TizenRest(HttpClient http, TizenOptions options) {
        this.http = http;
        this.options = options;
    }

    public Optional<TizenDeviceInfo> deviceInfo(String host) {
        return getJson(host, "/api/v2/").map(TizenRest::parseDeviceInfo);
    }

    public Optional<Boolean> appVisible(String host, String appId) {
        return getJson(host, "/api/v2/applications/" + URLEncoder.encode(appId, StandardCharsets.UTF_8))
                .map(app -> app.path("visible").asBoolean(false));
    }

    static TizenDeviceInfo parseDeviceInfo(JsonNode root) {
        JsonNode device = root.path("device");
        return new TizenDeviceInfo(
                device.path("name").asString(root.path("name").asString("")),
                device.path("modelName").asString(""),
                device.path("PowerState").asString(""),
                device.path("wifiMac").asString(""),
                device.path("TokenAuthSupport").asString("").equals("true"));
    }

    private Optional<JsonNode> getJson(String host, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(DeviceUris.of("http", host, options.restPort(), path))
                    .timeout(options.requestTimeout())
                    .GET().build();
            // A body over the cap is not a TV's small JSON: refused unread, as an IOException.
            HttpResponse<byte[]> response = DeviceFetch.send(http, request, MAX_BODY_BYTES, options.requestTimeout());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(TizenMessages.JSON.readTree(response.body()));
        } catch (IOException | JacksonException _) {
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
