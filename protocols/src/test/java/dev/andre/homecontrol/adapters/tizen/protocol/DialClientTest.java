package dev.andre.homecontrol.adapters.tizen.protocol;

import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DialClientTest {

    private FakeTizenServer fake;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTizenServer();
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private DialClient dial(int dialPort) {
        return dial(dialPort, Duration.ofSeconds(2));
    }

    private DialClient dial(int dialPort, Duration requestTimeout) {
        return new DialClient(InsecureTls.httpClient(Duration.ofSeconds(2)),
                new TizenOptions(fake.port(), fake.httpPort(), dialPort, "Home Control", Duration.ofSeconds(2),
                        requestTimeout));
    }

    @Test
    void startsYouTubeWithTheVideoParameter() throws Exception {
        dial(fake.httpPort()).launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ");

        assertThat(fake.nextDialBody()).isEqualTo("text/plain; charset=utf-8|v=aqz-KE-bpKQ");
    }

    @Test
    void aMissingDialAppIsADialException() {
        fake.setDialAvailable(false);

        assertThatThrownBy(() -> dial(fake.httpPort()).launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ"))
                .isInstanceOf(DialException.class)
                .hasMessage("YouTube is not available over DIAL on this TV");
    }

    @Test
    void aTvThatStallsItsAnswerFailsOnceTheWaitIsOver() {
        fake.stallAnswers();
        DialClient impatient = dial(fake.httpPort(), Duration.ofMillis(500));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> impatient.launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ"))
                        .isInstanceOf(IOException.class)
                        .isNotInstanceOf(DialException.class));
    }

    @ParameterizedTest
    @CsvSource({
            "503, The TV could not start YouTube right now",
            "500, The TV answered 500 when asked to start YouTube",
            "302, The TV answered 302 when asked to start YouTube"})
    void aLaunchTheTvDoesNotAcceptNamesItsAnswer(int status, String message) {
        fake.refuseDialWith(status);
        DialClient client = dial(fake.httpPort());

        assertThatThrownBy(() -> client.launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ"))
                .isInstanceOf(DialException.class)
                .hasMessage(message);
    }

    @Test
    void anUnreachableTvIsAnIoExceptionButNotADialException() throws IOException {
        DialClient client = dial(FakeWebSocketServer.closedPort());

        assertThatThrownBy(() -> client.launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ"))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(DialException.class);
    }
}
