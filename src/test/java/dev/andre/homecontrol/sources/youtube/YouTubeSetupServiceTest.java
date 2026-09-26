package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class YouTubeSetupServiceTest {

    private static final String VALID_CLIENT_ID = "123456789012-abc123def456.apps.googleusercontent.com";

    @TempDir
    Path tempDir;

    private SecretStore secrets;
    private LoginService login;
    private GoogleOAuthClient oauth;
    private GoogleTokens tokens;
    private YouTubeAuthorizationService authorization;
    private JsonFileSourceSettings sourceSettings;
    private YouTubeSetupService service;
    private HttpServletRequest httpRequest;
    private ObjectProvider<YouTubeAccount> account;
    private QuotaLedger ledger;
    private ObjectProvider<YouTubeContentSource> source;
    private ObjectProvider<YouTubePlaylists> playlists;
    private ObjectProvider<DeviceManager> devices;

    @BeforeEach
    void setUp() {
        secrets = mock(SecretStore.class);
        login = mock(LoginService.class);
        oauth = mock(GoogleOAuthClient.class);
        tokens = mock(GoogleTokens.class);
        authorization = mock(YouTubeAuthorizationService.class);
        sourceSettings = new JsonFileSourceSettings(tempDir.resolve("sources.json"));
        account = mock(ObjectProvider.class);
        ledger = mock(QuotaLedger.class);
        source = mock(ObjectProvider.class);
        playlists = mock(ObjectProvider.class);
        devices = mock(ObjectProvider.class);
        service = new YouTubeSetupService(secrets, login, sourceSettings, oauth, tokens, authorization, account, ledger,
                source, playlists, devices);
        httpRequest = mock(HttpServletRequest.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "123-abc.apps.googleusercontent.com.evil.org"})
    void connectValidatesTheClientId(String invalid) {
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(invalid, "GOCSPX-abc", "pw-1234567890", "pw-1234567890");

        assertThatThrownBy(() -> service.connect(request, httpRequest))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.INVALID_INPUT);
        assertThatThrownBy(() -> service.connect(request, httpRequest))
                .hasMessage("That does not look like an OAuth client ID (it ends in .apps.googleusercontent.com)");
    }

    @Test
    void aValidClientIdIsAccepted() {
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "GOCSPX-abc", "pw-1234567890", "pw-1234567890");

        service.connect(request, httpRequest);

        verify(authorization).start();
    }

    @Test
    void connectStoresBothSecretsWithTheLoginPasswordThenStarts() {
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "GOCSPX-abc", "pw-1234567890", "pw-1234567890");

        service.connect(request, httpRequest);

        InOrder order = inOrder(login, authorization);
        order.verify(login).storeSecrets(
                Map.of(YouTubeSettings.CLIENT_ID, VALID_CLIENT_ID, YouTubeSettings.CLIENT_SECRET, "GOCSPX-abc"),
                "pw-1234567890", "pw-1234567890", httpRequest);
        order.verify(authorization).start();
    }

    @Test
    void aBlankSecretKeepsTheStoredOne() {
        given(secrets.secret(YouTubeSettings.CLIENT_SECRET)).willReturn(Optional.of("stored-secret"));
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "  ", "pw-1234567890", "pw-1234567890");

        service.connect(request, httpRequest);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(login).storeSecrets(captor.capture(), anyString(), anyString(), any());
        assertThat(captor.getValue()).containsOnlyKeys(YouTubeSettings.CLIENT_ID);

        given(secrets.secret(YouTubeSettings.CLIENT_SECRET)).willReturn(Optional.empty());
        YouTubeSetupService.ConnectRequest noSecret =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "", "pw-1234567890", "pw-1234567890");
        assertThatThrownBy(() -> service.connect(noSecret, httpRequest))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.INVALID_INPUT);
        assertThatThrownBy(() -> service.connect(noSecret, httpRequest)).hasMessage("Enter the client secret");
    }

    @Test
    void aPasswordProblemStoresNothing() {
        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(login).storeSecrets(any(), any(), any(), any());
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "GOCSPX-abc", "pw-1", "pw-2");

        assertThatThrownBy(() -> service.connect(request, httpRequest))
                .isInstanceOf(PasswordRejectedException.class);
        verify(authorization, never()).start();
    }

    @Test
    void aClientIdChangeRemovesTheStoredRefreshToken() {
        String otherClientId = "987654321098-zzz999yyy888.apps.googleusercontent.com";
        given(secrets.secret(YouTubeSettings.CLIENT_ID)).willReturn(Optional.of(otherClientId));
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.of("rt"));
        given(secrets.names()).willReturn(Set.of(YouTubeSettings.CLIENT_ID, YouTubeSettings.CLIENT_SECRET, YouTubeSettings.REFRESH_TOKEN));
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "GOCSPX-abc", "pw-1234567890", "pw-1234567890");

        service.connect(request, httpRequest);

        verify(login).removeSecrets(List.of(YouTubeSettings.REFRESH_TOKEN));
        verify(tokens).reset();
    }

    @Test
    void anUnchangedClientIdKeepsTheStoredRefreshToken() {
        given(secrets.secret(YouTubeSettings.CLIENT_ID)).willReturn(Optional.of(VALID_CLIENT_ID));
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.of("rt"));
        given(secrets.names()).willReturn(Set.of(YouTubeSettings.CLIENT_ID, YouTubeSettings.CLIENT_SECRET, YouTubeSettings.REFRESH_TOKEN));
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "GOCSPX-abc", "pw-1234567890", "pw-1234567890");

        service.connect(request, httpRequest);

        verify(login, never()).removeSecrets(any());
        verify(tokens, never()).reset();
    }

    @Test
    void disconnectRevokesRemovesAndKeepsLounge() {
        YouTubeSettings original = new YouTubeSettings(Instant.parse("2026-09-16T10:00:00Z"), "chan-1", "Andre",
                true, Map.of("PLa", "Music"), Set.of("living-room"), "remote-1");
        service.save(original);
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.of("rt"));

        service.disconnect();

        verify(authorization).cancel();
        verify(oauth).revoke("rt");
        verify(login).removeSecrets(List.of(YouTubeSettings.CLIENT_ID, YouTubeSettings.CLIENT_SECRET, YouTubeSettings.REFRESH_TOKEN));
        verify(tokens).reset();
        assertThat(service.settings()).isEqualTo(original.withoutAccount());
    }

    @Test
    void aFailedRevokeStillDisconnects() {
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.of("rt"));
        doThrow(new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach Google")).when(oauth).revoke("rt");

        service.disconnect();

        verify(login).removeSecrets(List.of(YouTubeSettings.CLIENT_ID, YouTubeSettings.CLIENT_SECRET, YouTubeSettings.REFRESH_TOKEN));
        verify(tokens).reset();
    }

    @Test
    void disconnectWithoutARefreshTokenNeverCallsRevoke() {
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.empty());

        service.disconnect();

        verifyNoInteractions(oauth);
    }

    @Test
    void checkRefreshesTheToken() {
        given(tokens.accessToken()).willReturn("ya29.z");

        String result = service.check();

        InOrder order = inOrder(tokens);
        order.verify(tokens).invalidate();
        order.verify(tokens).accessToken();
        assertThat(result).isEqualTo("Google accepted the saved authorization");
    }

    @Test
    void connectRequestToStringHidesSecrets() {
        YouTubeSetupService.ConnectRequest request =
                new YouTubeSetupService.ConnectRequest(VALID_CLIENT_ID, "GOCSPX-secret", "pw-1234567890", "pw-0987654321");

        String text = request.toString();

        assertThat(text).doesNotContain("GOCSPX-secret").doesNotContain("pw-1234567890").doesNotContain("pw-0987654321");
    }

    @Test
    void checkNamesTheChannelAndQuota() {
        YouTubeAccount youTubeAccount = mock(YouTubeAccount.class);
        given(account.getIfAvailable()).willReturn(youTubeAccount);
        given(youTubeAccount.refreshChannel()).willReturn("Andre at Home");
        given(ledger.usage()).willReturn(new QuotaLedger.Usage(LocalDate.of(2026, 9, 16), 212, 10000, 0, 20,
                Map.of(), ZonedDateTime.now(ZoneId.of("Europe/Berlin"))));

        String result = service.check();

        assertThat(result).isEqualTo("Connected as Andre at Home. 212 of 10000 quota units used today.");
    }

    @Test
    void refreshChannelStoresTitleAndId() throws IOException {
        try (FakeGoogleServer fake = new FakeGoogleServer()) {
            fake.respondWhen("GET", "/youtube/v3/channels", r -> "true".equals(r.query().get("mine")),
                    FakeGoogleServer.Canned.fixture(200, "channels-mine.json"));
            QuotaLedger realLedger = new QuotaLedger(tempDir.resolve("account-quota.json"),
                    MutableClock.at(Instant.parse("2026-09-16T10:00:00Z")), 10000, 20);
            GoogleTokens accountTokens = mock(GoogleTokens.class);
            given(accountTokens.accessToken()).willReturn("ya29.a");
            YouTubeApiClient api = new YouTubeApiClient(new YouTubeHttp(fake.properties()),
                    URI.create(fake.base() + "/youtube/v3"), accountTokens, realLedger);
            YouTubeAccount youTubeAccount = new YouTubeAccount(api, service);

            String title = youTubeAccount.refreshChannel();

            assertThat(title).isEqualTo("Andre at Home");
            assertThat(service.settings().channelId()).isEqualTo("UC4fixtureHomeControl00a");
            assertThat(service.settings().channelTitle()).isEqualTo("Andre at Home");
            assertThat(realLedger.usage().calls()).isEqualTo(Map.of("channels.list", 1));
        }
    }

    @Test
    void choosePlaylistsStoresLoadedTitles() {
        YouTubePlaylists p = mock(YouTubePlaylists.class);
        given(playlists.getIfAvailable()).willReturn(p);
        given(p.loaded("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))
                .willReturn(Optional.of(new YouTubePlaylists.PlaylistSummary("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
                        "Watch this evening", 2)));

        service.choosePlaylists(List.of("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"));

        assertThat(service.settings().playlists()).containsExactly(
                Map.entry("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG", "Watch this evening"));

        service.choosePlaylists(List.of());

        assertThat(service.settings().playlists()).isEmpty();
    }

    @Test
    void unknownOrTooManyPlaylistsAreRefused() {
        YouTubePlaylists p = mock(YouTubePlaylists.class);
        given(playlists.getIfAvailable()).willReturn(p);
        given(p.loaded(anyString())).willReturn(Optional.empty());

        var preparedArg305_0 = List.of("PLnotLoaded00000000000000000000");
        assertThatThrownBy(() -> service.choosePlaylists(preparedArg305_0))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Load your playlists again, then choose");
        var preparedArg308_0 = List.of("../etc");
        assertThatThrownBy(() -> service.choosePlaylists(preparedArg308_0))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Load your playlists again, then choose");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String id = "PLtoomany" + String.format("%022d", i);
            tooMany.add(id);
            given(p.loaded(id)).willReturn(Optional.of(new YouTubePlaylists.PlaylistSummary(id, "T" + i, 0)));
        }
        assertThatThrownBy(() -> service.choosePlaylists(tooMany))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Choose at most 20 playlists");
    }

    @Test
    void watchLaterSwitch() {
        service.setWatchLater(true);

        assertThat(service.settings().watchLater()).isTrue();

        service.setWatchLater(false);

        assertThat(service.settings().watchLater()).isFalse();
    }

    @Test
    void loungeSwitchNeedsACastDevice() {
        DeviceManager manager = mock(DeviceManager.class);
        given(devices.getIfAvailable()).willReturn(manager);
        given(manager.device("kitchen")).willReturn(Optional.of(new Device("kitchen", "Kitchen", DeviceKind.CAST,
                "10.0.0.9", Map.of("cast", Map.of()), Instant.now())));
        given(manager.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER));
        given(manager.device("living")).willReturn(Optional.of(new Device("living", "Living Room", DeviceKind.WEBOS,
                "10.0.0.7", Map.of("webos", Map.of()), Instant.now())));
        given(manager.capabilities("living")).willReturn(EnumSet.of(Capability.APP_LINK));
        given(manager.device("gone")).willReturn(Optional.empty());

        assertThat(service.setLounge("kitchen", true)).isEqualTo("Kitchen");
        assertThat(service.settings().loungeDevices()).containsExactly("kitchen");

        assertThatThrownBy(() -> service.setLounge("living", true))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("Only Cast devices can use YouTube Cast")
                .extracting(e -> ((YouTubeException) e).kind()).isEqualTo(YouTubeException.Kind.INVALID_INPUT);
        assertThatThrownBy(() -> service.setLounge("gone", true))
                .isInstanceOf(YouTubeException.class)
                .hasMessage("No device with id gone")
                .extracting(e -> ((YouTubeException) e).kind()).isEqualTo(YouTubeException.Kind.INVALID_INPUT);

        service.save(service.settings().withLoungeDevice("gone", true));
        assertThat(service.setLounge("gone", false)).isEqualTo("gone");
        assertThat(service.settings().loungeDevices()).containsExactly("kitchen");

        assertThat(service.setLounge("kitchen", false)).isEqualTo("Kitchen");
        assertThat(service.settings().loungeDevices()).isEmpty();
    }
}
