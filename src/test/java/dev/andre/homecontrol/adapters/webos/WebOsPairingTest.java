package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebOsPairingTest {

    private final DeviceManager devices = mock(DeviceManager.class);
    private final SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0, 60, 2));
    private FakeSsapServer tv;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        tv = new FakeSsapServer(false);
        when(devices.attach(any(), any(), any(), any(), any())).thenAnswer(call -> new Device("webos-127-0-0-1",
                call.getArgument(1), call.getArgument(2), call.getArgument(0),
                Map.of(call.getArgument(3), (Map<String, String>) call.getArgument(4)), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        tv.close();
    }

    private WebOsPairing pairing(int port, int pairingTimeoutSeconds) throws IOException {
        return new WebOsPairing(new WebOsProperties(true, port, FakeWebSocketServer.closedPort(), 2, 2,
                pairingTimeoutSeconds, 1, 2, 0), ssdp, devices);
    }

    @Test
    void anAcceptedPromptStoresTheClientKeyThroughTheDeviceManager() throws IOException {
        tv.setPrompt(FakeSsapServer.Prompt.ACCEPT);

        PromptPairingResult result = pairing(tv.port(), 2).pair("127.0.0.1", "Living Room TV");

        assertThat(result).isInstanceOf(PromptPairingResult.Paired.class);
        verify(devices).attach("127.0.0.1", "Living Room TV", DeviceKind.WEBOS, "webos",
                Map.of("clientKey", FakeSsapServer.CLIENT_KEY));
    }

    @Test
    void withoutANameItUsesTheModelName() throws IOException {
        pairing(tv.port(), 2).pair("127.0.0.1", " ");

        verify(devices).attach(eq("127.0.0.1"), eq("LG OLED55C9PLA"), eq(DeviceKind.WEBOS), eq("webos"), anyMap());
    }

    @Test
    void aDeclinedPromptIsDeclined() throws IOException {
        tv.setPrompt(FakeSsapServer.Prompt.DECLINE);

        PromptPairingResult result = pairing(tv.port(), 2).pair("127.0.0.1", "LG");

        assertThat(result).isInstanceOfSatisfying(PromptPairingResult.Declined.class,
                declined -> assertThat(declined.reason()).contains("declined"));
        verify(devices, never()).attach(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void anUnansweredPromptFailsAfterTheTimeout() throws IOException {
        tv.setPrompt(FakeSsapServer.Prompt.IGNORE);

        PromptPairingResult result = pairing(tv.port(), 1).pair("127.0.0.1", "LG");

        assertThat(result).isInstanceOfSatisfying(PromptPairingResult.Failed.class,
                failed -> assertThat(failed.reason()).contains("within 1 seconds"));
        verify(devices, never()).attach(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void anUnreachableHostFails() throws IOException {
        PromptPairingResult result = pairing(FakeWebSocketServer.closedPort(), 2).pair("127.0.0.1", "LG");

        assertThat(result).isInstanceOfSatisfying(PromptPairingResult.Failed.class,
                failed -> assertThat(failed.reason()).contains("Could not reach an LG webOS TV at 127.0.0.1"));
    }

    @Test
    void describesItselfForTheSetupPage() throws IOException {
        WebOsPairing pairing = pairing(tv.port(), 60);

        assertThat(pairing.adapterId()).isEqualTo("webos");
        assertThat(pairing.displayName()).isEqualTo("LG webOS TV");
        assertThat(pairing.instructions()).contains("60 seconds");
    }
}
