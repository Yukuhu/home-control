package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.SecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The login gate through the real servlet container: only the exact raw paths {@code /login} and
 * {@code /app.css} are open, whatever the container decodes or strips, and a state stream ends
 * when its browser logs out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginGateEndToEndTest {

    static final String PASSWORD = "household password";

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("login-gate-e2e").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @LocalServerPort
    int port;

    @Autowired
    LoginService login;

    @Autowired
    SecretStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void storeAFirstSecret() {
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new MockHttpServletRequest());
    }

    @AfterEach
    void removeTheSecrets() {
        login.removeSecrets(store.names());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private int get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = request(path).GET();
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String logIn() throws Exception {
        HttpResponse<Void> response = http.send(request("/login")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Origin", "http://localhost:" + port)
                        .POST(HttpRequest.BodyPublishers.ofString("password=household+password&next=%2Fsetup"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void onlyTheExactLoginAndStylesheetPathsAreOpen() throws Exception {
        assertThat(get("/login", null)).isEqualTo(200);
        assertThat(get("/app.css", null)).isEqualTo(200);

        for (String path : new String[] {"/login;x", "/login;jsessionid=1", "/%6cogin", "/app.css;x", "/%61pp.css",
                "/login/../setup", "/app.css;/../setup", "/setup", "/setup;x", "/%73etup", "/events"}) {
            assertThat(get(path, null)).as(path).isIn(400, 401);
        }
    }

    @Test
    void aLoggedInBrowserReachesEverySpellingOfAPath() throws Exception {
        String cookie = logIn();

        assertThat(get("/setup", cookie)).isEqualTo(200);
        assertThat(get("/setup;x", cookie)).isEqualTo(200);
        assertThat(get("/%73etup", cookie)).isEqualTo(200);
    }

    @Test
    void theStateStreamEndsWhenItsBrowserLogsOut() throws Exception {
        String cookie = logIn();
        // No devices, so no snapshot frame: the response only completes when the stream ends.
        CompletableFuture<HttpResponse<String>> events = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events")).header("Cookie", cookie)
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1_000);
        assertThat(events).as("the stream stays open while logged in").isNotDone();

        HttpResponse<Void> logout = http.send(request("/logout").header("Cookie", cookie)
                        .header("Origin", "http://localhost:" + port)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(logout.statusCode()).isEqualTo(302);

        await().atMost(Duration.ofSeconds(10)).until(events::isDone);
        assertThat(events.get().statusCode()).isEqualTo(200);
    }
}
