package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return new DialClient(InsecureTls.httpClient(Duration.ofSeconds(2)),
                new TizenProperties(true, fake.port(), fake.httpPort(), dialPort, "Home Control", 2, 2, 2, 1, 0));
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
    void anUnreachableTvIsAnIoExceptionButNotADialException() throws IOException {
        DialClient client = dial(FakeWebSocketServer.closedPort());

        assertThatThrownBy(() -> client.launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ"))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(DialException.class);
    }
}
