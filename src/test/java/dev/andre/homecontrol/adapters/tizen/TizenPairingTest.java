package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.DeviceEnrollment;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.PromptPairingResult;
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

class TizenPairingTest {

    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final DeviceEnrollment enrollment = mock(DeviceEnrollment.class);
    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();
    private FakeTizenServer tv;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        tv = new FakeTizenServer();
        when(enrollment.attach(any(), any(), any(), any(), any())).thenAnswer(call -> new Device("tizen-127-0-0-1",
                call.getArgument(1), call.getArgument(2), call.getArgument(0),
                Map.of(call.getArgument(3), (Map<String, String>) call.getArgument(4)), Instant.now()));
    }

    @AfterEach
    void tearDown() {
        tv.close();
    }

    private TizenPairing pairing(int port, int pairingTimeoutSeconds) {
        return new TizenPairing(new TizenProperties(true, port, tv.httpPort(), tv.httpPort(), "Home Control",
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(pairingTimeoutSeconds), Duration.ofSeconds(1), Duration.ofSeconds(0)), devices, enrollment, secrets);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> attachedSettings() {
        ArgumentCaptor<Map<String, String>> settings = ArgumentCaptor.forClass(Map.class);
        verify(enrollment).attach(eq("127.0.0.1"), any(), eq(DeviceKind.TIZEN), eq("tizen"), settings.capture());
        return settings.getValue();
    }

    @Test
    void anAllowedConnectionStoresThePairingAndToken() {
        tv.setAuthorization(FakeTizenServer.Authorization.ALLOW);

        PromptPairingResult result = pairing(tv.port(), 2).pair("127.0.0.1", null);

        assertThat(result).isInstanceOf(PromptPairingResult.Paired.class);
        verify(enrollment).attach(eq("127.0.0.1"), eq("[TV] Samsung 8 Series (55)"), eq(DeviceKind.TIZEN), eq("tizen"), any());
        Map<String, String> settings = attachedSettings();
        assertThat(settings).containsOnlyKeys("paired", "keyRef").containsEntry("paired", "true");
        assertThat(secrets.deviceSecret(TizenSettings.secretName(settings.get("keyRef")))).contains("73184052");
        assertThat(tv.queries().getLast()).doesNotContain("token=");
    }

    @Test
    void repairingTheSameTvReusesItsReference() {
        when(devices.devices()).thenReturn(List.of(new Device("tizen-127-0-0-1", "Samsung", DeviceKind.TIZEN, "127.0.0.1",
                Map.of("tizen", Map.of("paired", "true", "keyRef", "0123456789abcdef")), Instant.now())));
        tv.setAuthorization(FakeTizenServer.Authorization.ALLOW);

        pairing(tv.port(), 2).pair("127.0.0.1", null);

        assertThat(attachedSettings()).isEqualTo(Map.of("paired", "true", "keyRef", "0123456789abcdef"));
        assertThat(secrets.all()).containsOnlyKeys("device.tizen.0123456789abcdef.token");
    }

    @Test
    void theUsersNameWins() {
        pairing(tv.port(), 2).pair("127.0.0.1", "Bedroom TV");

        verify(enrollment).attach(eq("127.0.0.1"), eq("Bedroom TV"), eq(DeviceKind.TIZEN), eq("tizen"), anyMap());
    }

    @Test
    void firmwareWithoutTokensStillPairs() {
        tv.setIssueTokens(false);

        pairing(tv.port(), 2).pair("127.0.0.1", "TV");

        verify(enrollment).attach("127.0.0.1", "TV", DeviceKind.TIZEN, "tizen", Map.of("paired", "true"));
    }

    @Test
    void aDeniedConnectionIsDeclined() {
        tv.setAuthorization(FakeTizenServer.Authorization.DENY);

        assertThat(pairing(tv.port(), 2).pair("127.0.0.1", "TV")).isInstanceOfSatisfying(PromptPairingResult.Declined.class,
                declined -> assertThat(declined.reason()).contains("declined"));
        verify(enrollment, never()).attach(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void anUnansweredPromptFails() {
        tv.setAuthorization(FakeTizenServer.Authorization.IGNORE);

        assertThat(pairing(tv.port(), 1).pair("127.0.0.1", "TV")).isInstanceOfSatisfying(PromptPairingResult.Failed.class,
                failed -> assertThat(failed.reason()).contains("within 1 seconds"));
        verify(enrollment, never()).attach(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void anUnreachableHostFails() throws IOException {
        assertThat(pairing(FakeWebSocketServer.closedPort(), 2).pair("127.0.0.1", "TV"))
                .isInstanceOfSatisfying(PromptPairingResult.Failed.class,
                        failed -> assertThat(failed.reason()).contains("Could not reach a Samsung TV at 127.0.0.1"));
    }

    @Test
    void describesItselfForTheSetupPage() {
        TizenPairing pairing = new TizenPairing(new TizenProperties(true, 8002, 8001, 8080, "Home Control",
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(3)), devices, enrollment, secrets);

        assertThat(pairing.adapterId()).isEqualTo("tizen");
        assertThat(pairing.displayName()).isEqualTo("Samsung TV");
        assertThat(pairing.instructions()).contains("Allow").contains("30 seconds");
    }
}
