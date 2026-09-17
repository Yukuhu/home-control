package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TizenRemoteConnectionTest {

    private final HttpClient http = InsecureTls.httpClient(Duration.ofSeconds(2));
    private final BlockingQueue<String> reasons = new LinkedBlockingQueue<>();
    private FakeTizenServer fake;
    private TizenRemoteConnection connection;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTizenServer();
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
        fake.close();
    }

    private TizenRemoteConnection open(String token) throws IOException {
        connection = TizenRemoteConnection.open(http, "127.0.0.1", TizenRestTest.properties(fake), token, reasons::add);
        return connection;
    }

    @Test
    void aKnownTokenConnectsWithoutAPrompt() throws IOException {
        TizenRemoteConnection opened = open(FakeTizenServer.TOKEN);

        assertThat(opened.awaitAuthorization(Duration.ofSeconds(2))).isEqualTo(TizenRemoteConnection.Authorization.CONNECTED);
        assertThat(opened.token()).isEmpty();
        assertThat(fake.queries().getLast()).contains("name=SG9tZSBDb250cm9s&token=73184052");
    }

    @Test
    void anAllowedPromptHandsOutAToken() throws IOException {
        fake.setAuthorization(FakeTizenServer.Authorization.ALLOW);
        TizenRemoteConnection opened = open(null);

        assertThat(opened.awaitAuthorization(Duration.ofSeconds(2))).isEqualTo(TizenRemoteConnection.Authorization.CONNECTED);
        assertThat(opened.token()).contains(FakeTizenServer.TOKEN);
        assertThat(fake.queries().getLast()).doesNotContain("token=");
    }

    @Test
    void aDeniedPromptIsUnauthorized() throws IOException {
        fake.setAuthorization(FakeTizenServer.Authorization.DENY);

        assertThat(open(null).awaitAuthorization(Duration.ofSeconds(2))).isEqualTo(TizenRemoteConnection.Authorization.UNAUTHORIZED);
    }

    @Test
    void anUnansweredPromptIsNoAnswer() throws IOException {
        fake.setAuthorization(FakeTizenServer.Authorization.IGNORE);

        assertThat(open(null).awaitAuthorization(Duration.ofSeconds(1))).isEqualTo(TizenRemoteConnection.Authorization.NO_ANSWER);
    }

    @Test
    void sendsKeysAndLaunchesAndLearnsInstalledApps() throws Exception {
        TizenRemoteConnection opened = open(FakeTizenServer.TOKEN);
        opened.awaitAuthorization(Duration.ofSeconds(2));

        opened.key("KEY_HOME");
        assertThat(fake.nextKey()).isEqualTo("KEY_HOME");
        opened.launchApp(FakeTizenServer.NETFLIX, "DEEP_LINK");
        assertThat(fake.nextLaunch()).isEqualTo("3201907018807");
        opened.requestInstalledApps();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(opened.installedApps())
                .hasValueSatisfying(apps -> assertThat(apps).contains(new TizenApp("111299001912", "YouTube", 2))));
    }

    @Test
    void theTvClosingTheConnectionIsReportedOnce() throws Exception {
        open(FakeTizenServer.TOKEN).awaitAuthorization(Duration.ofSeconds(2));

        fake.dropConnections();

        assertThat(reasons.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(reasons.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }
}
