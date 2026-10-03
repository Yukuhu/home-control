package dev.andre.homecontrol.adapters.tizen.protocol;

import dev.andre.homecontrol.adapters.net.DeviceUris;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * DIAL application launch (DIAL 2.x §6.1): {@code POST http://host:8080/ws/apps/<app>} with the app's arguments. The
 * request timeout bounds the whole answer, so a TV that stalls mid-body cannot hold the caller.
 */
public final class DialClient {

    /** A launch answers with no body, a refusal with a short error page at most; anything past this is refused. */
    static final int MAX_ANSWER_BYTES = 64 * 1024;

    private final HttpClient http;
    private final TizenOptions options;

    public DialClient(HttpClient http, TizenOptions options) {
        this.http = http;
        this.options = options;
    }

    public void launch(String host, String app, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(DeviceUris.of("http", host, options.dialPort(), "/ws/apps/" + app))
                .timeout(options.requestTimeout())
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = DeviceFetch.send(http, request, MAX_ANSWER_BYTES, options.requestTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting " + app, e);
        }
        switch (response.statusCode()) {
            case 200, 201 -> { // DIAL accepted the launch; a body, if any, says nothing more.
            }
            case 404 -> throw new DialException(app + " is not available over DIAL on this TV");
            case 503 -> throw new DialException("The TV could not start " + app + " right now");
            default -> throw new DialException("The TV answered " + response.statusCode() + " when asked to start " + app);
        }
    }
}
