package dev.andre.homecontrol.adapters.tizen.protocol;

import dev.andre.homecontrol.adapters.net.DeviceUris;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** DIAL application launch (DIAL 2.x §6.1): {@code POST http://host:8080/ws/apps/<app>} with the app's arguments. */
public final class DialClient {

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
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting " + app, e);
        }
        switch (response.statusCode()) {
            case 200, 201 -> { // DIAL accepted the launch; there is no response body to read.
            }
            case 404 -> throw new DialException(app + " is not available over DIAL on this TV");
            case 503 -> throw new DialException("The TV could not start " + app + " right now");
            default -> throw new DialException("The TV answered " + response.statusCode() + " when asked to start " + app);
        }
    }
}
