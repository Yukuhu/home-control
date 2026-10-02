package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.webos.protocol.FakeSsapServer;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import dev.andre.homecontrol.testsupport.InMemoryDeviceSecrets;
import java.time.Duration;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
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

    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final DeviceEnrollment enrollment = mock(DeviceEnrollment.class);
    private final SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0,
            Duration.ofSeconds(60), 2));
    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();
    private FakeSsapServer tv;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        tv = new FakeSsapServer(false);
        when(enrollment.attach(any(), any(), any(), any(), any())).thenAnswer(call -> new Device("webos-127-0-0-1",
                call.getArgument(1), call.getArgument(2), call.getArgument(0),
                Map.of(call.getArgument(3), (Map<String, String>) call.getArgument(4)), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        tv.close();
    }

    private WebOsPairing pairing(int port, int pairingTimeoutSeconds) throws IOException {
        return new WebOsPairing(new WebOsProperties(true, port, FakeWebSocketServer.closedPort(),
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(pairingTimeoutSeconds), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(0)),
                ssdp, devices, enrollment, secrets);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> attachedSettings() {
        ArgumentCaptor<Map<String, String>> settings = ArgumentCaptor.forClass(Map.class);
        verify(enrollment).attach(eq("127.0.0.1"), any(), eq(DeviceKind.WEBOS), eq("webos"), settings.capture());
        return settings.getValue();
    }

    @Test
    void anAcceptedPromptStoresTheClientKeyThroughEnrollment() throws IOException {
        tv.setPrompt(FakeSsapServer.Prompt.ACCEPT);

        PromptPairingResult result = pairing(tv.port(), 2).pair("127.0.0.1", "Living Room TV");

        assertThat(result).isInstanceOf(PromptPairingResult.Paired.class);
        Map<String, String> settings = attachedSettings();
        assertThat(settings).containsOnlyKeys("keyRef");
        assertThat(secrets.deviceSecret(WebOsSettings.secretName(settings.get("keyRef")))).contains(FakeSsapServer.CLIENT_KEY);
    }

    @Test
    void repairingTheSameTvReusesItsReference() throws IOException {
        when(devices.devices()).thenReturn(List.of(new Device("webos-127-0-0-1", "LG", DeviceKind.WEBOS, "127.0.0.1",
                Map.of("webos", Map.of("keyRef", "0123456789abcdef")), Instant.now())));
        tv.setPrompt(FakeSsapServer.Prompt.ACCEPT);

        pairing(tv.port(), 2).pair("127.0.0.1", "Living Room TV");

        assertThat(attachedSettings()).isEqualTo(Map.of("keyRef", "0123456789abcdef"));
        assertThat(secrets.all()).containsOnlyKeys("device.webos.0123456789abcdef.client-key");
    }

    @Test
    void withoutANameItUsesTheModelName() throws IOException {
        pairing(tv.port(), 2).pair("127.0.0.1", " ");

        verify(enrollment).attach(eq("127.0.0.1"), eq("LG OLED55C9PLA"), eq(DeviceKind.WEBOS), eq("webos"), anyMap());
    }

    @Test
    void aDeclinedPromptIsDeclined() throws IOException {
        tv.setPrompt(FakeSsapServer.Prompt.DECLINE);

        PromptPairingResult result = pairing(tv.port(), 2).pair("127.0.0.1", "LG");

        assertThat(result).isInstanceOfSatisfying(PromptPairingResult.Declined.class,
                declined -> assertThat(declined.reason()).contains("declined"));
        verify(enrollment, never()).attach(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void anUnansweredPromptFailsAfterTheTimeout() throws IOException {
        tv.setPrompt(FakeSsapServer.Prompt.IGNORE);

        PromptPairingResult result = pairing(tv.port(), 1).pair("127.0.0.1", "LG");

        assertThat(result).isInstanceOfSatisfying(PromptPairingResult.Failed.class,
                failed -> assertThat(failed.reason()).contains("within 1 seconds"));
        verify(enrollment, never()).attach(anyString(), anyString(), any(), anyString(), anyMap());
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
        assertThat(pairing.instructions()).contains("within 1 minute");
    }
}
