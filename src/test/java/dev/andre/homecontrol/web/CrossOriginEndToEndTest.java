package dev.andre.homecontrol.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-origin check through the real servlet container, whose path handling ({@code ;params},
 * percent-decoding) differs from MockMvc's: no spelling of a path may reach a controller cross-site.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossOriginEndToEndTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("cross-origin-e2e").toString();
        registry.add("shield.data-dir", () -> dataDir);
        registry.add("home-control.security.allowed-hosts", () -> "tv.example.org, *.home.example.net");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private int post(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/nope/key/HOME", "/devices;x/nope/key/HOME", "/devices/nope;x/key/HOME",
            "/%64evices/nope/key/HOME", "/devices/%6eope/key/HOME", "/setup/sources/youtube/lounge"})
    void aCrossSiteRequestIsRefusedWhateverThePathLooksLike(String path) throws Exception {
        assertThat(post(path, "Sec-Fetch-Site", "cross-site", "Origin", "http://evil.example")).isEqualTo(403);
        assertThat(post(path, "Origin", "http://evil.example")).isEqualTo(403);
        assertThat(post(path, "Origin", "http://localhost:" + (port + 1))).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/nope/key/HOME", "/devices;x/nope/key/HOME", "/%64evices/nope/key/HOME"})
    void sameOriginRequestsReachTheApplication(String path) throws Exception {
        assertThat(post(path, "Sec-Fetch-Site", "same-origin", "Origin", "http://localhost:" + port)).isEqualTo(404);
        assertThat(post(path, "Origin", "http://localhost:" + port)).isEqualTo(404);
        assertThat(post(path)).isEqualTo(404);
    }

    /** HttpClient will not send a Host of our choosing, so these requests are written by hand. */
    private String raw(String method, String path, String host, String... headers) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n");
            for (int i = 0; i < headers.length; i += 2) {
                request.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
            request.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static int status(String response) {
        return Integer.parseInt(response.substring(9, 12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /app.css", "GET /events", "POST /setup/forget", "POST /login"})
    void aRebindingHostIsMisdirectedWithoutReflectingIt(String request) throws Exception {
        String[] parts = request.split(" ");
        String response = raw(parts[0], parts[1], "evil.example:" + port,
                "Origin", "http://evil.example:" + port, "Sec-Fetch-Site", "same-origin");

        assertThat(status(response)).isEqualTo(421);
        assertThat(response).doesNotContain("evil");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1", "[::1]", "tv.local", "nas", "tv.example.org", "a.home.example.net"})
    void lanAndConfiguredHostsAreServed(String hostName) throws Exception {
        String host = hostName + ":" + port;

        assertThat(status(raw("GET", "/app.css", host))).isEqualTo(200);
        assertThat(status(raw("POST", "/devices/nope/key/HOME", host, "Origin", "http://" + host))).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.org", "home.example.net", "tv.example.org.evil.example"})
    void hostsOutsideTheConfiguredListAreMisdirected(String hostName) throws Exception {
        assertThat(status(raw("GET", "/app.css", hostName + ":" + port))).isEqualTo(421);
    }
}
