package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import dev.andre.homecontrol.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;

class YouTubeAuthorizationServiceTest {

    @TempDir
    Path tempDir;

    private FakeGoogleServer fake;
    private MutableClock clock;
    private SecretStore secrets;
    private GoogleTokens tokens;
    private JsonFileSourceSettings sourceSettings;
    private YouTubeAuthorizationService authorization;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        GoogleOAuthClient oauth = new GoogleOAuthClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/oauth"), clock);
        secrets = mock(SecretStore.class);
        given(secrets.secret(YouTubeSettings.CLIENT_ID)).willReturn(Optional.of("cid"));
        given(secrets.secret(YouTubeSettings.CLIENT_SECRET)).willReturn(Optional.of("csecret"));
        tokens = mock(GoogleTokens.class);
        sourceSettings = new JsonFileSourceSettings(tempDir.resolve("sources.json"));
        authorization = new YouTubeAuthorizationService(oauth, secrets, tokens, sourceSettings, clock, false);
    }

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void startShowsTheCodeButNeverTheDeviceCode() {
        YouTubeAuthorizationService.Status status = authorization.start();

        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
        assertThat(status.userCode()).isEqualTo("GQVQ-JKEC");
        assertThat(status.verificationUrl()).isEqualTo(URI.create("https://www.google.com/device"));
        assertThat(status.expiresAt()).isEqualTo(Instant.parse("2026-09-16T10:30:00Z"));
        assertThat(authorization.status().toString()).doesNotContain("AH-1Ng2m");
    }

    @Test
    void theStatusAnswersWhileACodeIsRequested() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        fake.holdWhen("POST", "/oauth/device/code", request -> true, release,
                FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));
        CompletableFuture<YouTubeAuthorizationService.Status> starting =
                CompletableFuture.supplyAsync(authorization::start);
        await().until(() -> fake.count("/oauth/device/code") == 1);

        try {
            // Every setup render asks for the status; Google may take its time.
            assertThat(CompletableFuture.supplyAsync(authorization::status).get(2, TimeUnit.SECONDS).state())
                    .isEqualTo(YouTubeAuthorizationService.State.IDLE);
        } finally {
            release.countDown();
        }
        assertThat(starting.get(5, TimeUnit.SECONDS).state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
    }

    @Test
    void theStatusAnswersWhileTheConnectHookRuns() throws Exception {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        CountDownLatch hookRuns = new CountDownLatch(1);
        CountDownLatch hookMayEnd = new CountDownLatch(1);
        // The real hook clears the feeds and looks up the channel: calls to Google of their own.
        authorization.onConnected(() -> {
            hookRuns.countDown();
            try {
                hookMayEnd.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        authorization.start();
        clock.advance(Duration.ofSeconds(5));
        CompletableFuture<Boolean> polling = CompletableFuture.supplyAsync(authorization::pollOnce);
        assertThat(hookRuns.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            // Not CONNECTED yet: the setup page reloads on that, and should then show the channel.
            assertThat(CompletableFuture.supplyAsync(authorization::status).get(2, TimeUnit.SECONDS).state())
                    .isEqualTo(YouTubeAuthorizationService.State.PENDING);
        } finally {
            hookMayEnd.countDown();
        }
        assertThat(polling.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.CONNECTED);
    }

    @Test
    void aCodeReplacedWhileGoogleAnswersIsNotGranted() throws Exception {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        authorization.start();
        clock.advance(Duration.ofSeconds(5));
        CountDownLatch release = new CountDownLatch(1);
        fake.holdWhen("POST", "/oauth/device/code", request -> true, release,
                FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));
        CompletableFuture<YouTubeAuthorizationService.Status> replacing =
                CompletableFuture.supplyAsync(authorization::start);
        await().until(() -> fake.count("/oauth/device/code") == 2);

        // The background poll comes due while the new code is requested: the old code is no longer the user's.
        try {
            authorization.pollOnce();
        } finally {
            release.countDown();
        }

        assertThat(replacing.get(5, TimeUnit.SECONDS).state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
        verify(secrets, never()).putSecrets(any());
    }

    @Test
    void aGrantThatCannotBeKeptEndsAsFailed() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        doThrow(new IllegalStateException("the data directory is read-only")).when(secrets).putSecrets(any());
        authorization.start();
        clock.advance(Duration.ofSeconds(5));

        assertThat(authorization.pollOnce()).isFalse();

        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.FAILED);
        assertThat(authorization.status().message()).contains("the data directory is read-only");
    }

    @Test
    void aStartCancelledWhileGoogleAnswersShowsNoCode() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        fake.holdWhen("POST", "/oauth/device/code", request -> true, release,
                FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));
        CompletableFuture<YouTubeAuthorizationService.Status> starting =
                CompletableFuture.supplyAsync(authorization::start);
        await().until(() -> fake.count("/oauth/device/code") == 1);

        authorization.cancel();
        release.countDown();

        assertThat(starting.get(5, TimeUnit.SECONDS).state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
    }

    @Test
    void pollsOnlyWhenDue() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(428, "oauth-token-pending.json"));
        authorization.start();

        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isZero();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(1);
    }

    @Test
    void pendingWaitsTheInterval() {
        fake.oauthApproves();
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(1);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);

        clock.advance(Duration.ofSeconds(4));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(2);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.CONNECTED);
    }

    @Test
    void slowDownAddsFiveSeconds() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(403, "oauth-token-slow-down.json"),
                FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(9));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isEqualTo(2);
    }

    @Test
    void aGrantStoresTheRefreshTokenAndPrimesTheAccessToken() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        AtomicInteger connectedCount = new AtomicInteger();
        authorization.onConnected(connectedCount::incrementAndGet);
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        boolean stillPending = authorization.pollOnce();

        assertThat(stillPending).isFalse();
        InOrder order = inOrder(tokens);
        order.verify(tokens).reset();
        order.verify(tokens).prime(new GoogleOAuthClient.AccessToken("ya29.a0AfB_byFixtureAccessTokenGranted0001",
                Instant.parse("2026-09-16T11:00:04Z")));
        verify(secrets).putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, "1//0gFixtureRefreshTokenGranted-0001"));

        YouTubeAuthorizationService.Status status = authorization.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.CONNECTED);
        assertThat(status.message()).isEqualTo("YouTube connected");

        YouTubeSettings settings = YouTubeSettings.read(sourceSettings);
        assertThat(settings.connectedAt()).isEqualTo(clock.instant());
        assertThat(connectedCount.get()).isEqualTo(1);
        assertThat(authorization.pollOnce()).isFalse();
    }

    @Test
    void aDenialEndsThePending() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(403, "oauth-token-denied.json"));
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();

        YouTubeAuthorizationService.Status status = authorization.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.DENIED);
        assertThat(status.message()).isEqualTo("Access was denied on the Google page. Start again to retry.");
    }

    @Test
    void anExpiredTokenEndsThePending() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(400, "oauth-token-expired.json"));
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();

        YouTubeAuthorizationService.Status status = authorization.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.EXPIRED);
        assertThat(status.message()).isEqualTo("The code expired before it was entered. Start again.");
    }

    @Test
    void aLocalExpiryEndsThePendingWithoutARequest() {
        authorization.start();

        clock.advance(Duration.ofMinutes(31));
        authorization.pollOnce();

        assertThat(fake.count("/oauth/token")).isZero();
        YouTubeAuthorizationService.Status status = authorization.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.EXPIRED);
    }

    @Test
    void aFailedResultEndsThePending() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Malformed auth code.\"}"));
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();

        YouTubeAuthorizationService.Status status = authorization.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.FAILED);
        assertThat(status.message()).isEqualTo("Google refused the authorization (invalid_grant: Malformed auth code.)");
    }

    @Test
    void networkTroubleKeepsPolling() {
        authorization.start();
        fake.close();

        clock.advance(Duration.ofSeconds(5));
        boolean stillPending = authorization.pollOnce();

        assertThat(stillPending).isTrue();
        YouTubeAuthorizationService.Status status = authorization.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
        assertThat(status.message()).isEqualTo("Could not reach Google; still trying");
    }

    @Test
    void aBusyConnectionKeepsPolling() {
        GoogleOAuthClient oauth = spy(new GoogleOAuthClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/oauth"), clock));
        doThrow(new YouTubeException(ContentSourceException.Kind.RATE_LIMITED,
                "Home Control is busy talking to Google; try again in a moment")).when(oauth).poll(any(), any(), any());
        YouTubeAuthorizationService busy = new YouTubeAuthorizationService(oauth, secrets, tokens, sourceSettings,
                clock, false);
        busy.start();

        clock.advance(Duration.ofSeconds(5));
        boolean stillPending = busy.pollOnce();

        assertThat(stillPending).isTrue();
        YouTubeAuthorizationService.Status status = busy.status();
        assertThat(status.state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
        assertThat(status.message()).isEqualTo("Home Control is busy talking to Google; still trying");
    }

    @Test
    void cancelReturnsToIdle() {
        authorization.start();
        authorization.cancel();

        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);

        authorization.pollOnce();
        assertThat(fake.count("/oauth/token")).isZero();
    }

    @Test
    void startWithoutClientIsRefused() {
        given(secrets.secret(YouTubeSettings.CLIENT_ID)).willReturn(Optional.empty());
        given(secrets.secret(YouTubeSettings.CLIENT_SECRET)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authorization.start())
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(ContentSourceException.Kind.NOT_CONFIGURED);
        assertThatThrownBy(() -> authorization.start()).hasMessage("Save the OAuth client ID and secret first");
    }

    @Test
    void aSecondStartReplacesThePending() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(428, "oauth-token-pending.json"));
        authorization.start();
        authorization.start();

        assertThat(fake.count("/oauth/device/code")).isEqualTo(2);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
    }

    @Test
    void startWithAClientIdButNoSecretIsRefusedBeforeAskingGoogle() {
        given(secrets.secret(YouTubeSettings.CLIENT_SECRET)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authorization.start()).hasMessage("Save the OAuth client ID and secret first");
        assertThat(fake.count("/oauth/device/code")).isZero();
    }

    @Test
    void aGoogleServerErrorKeepsPolling() {
        YouTubeAuthorizationService failing = withPoll(service -> {
            throw new YouTubeException(ContentSourceException.Kind.SERVER_ERROR, "Google answered HTTP 503");
        });
        failing.start();

        clock.advance(Duration.ofSeconds(5));

        assertThat(failing.pollOnce()).isTrue();
        assertThat(failing.status().state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
        assertThat(failing.status().userCode()).isEqualTo("GQVQ-JKEC");
        assertThat(failing.status().message()).isEqualTo("Could not reach Google; still trying");
    }

    @Test
    void aPollGoogleRefusesEndsWithItsReason() {
        YouTubeAuthorizationService refused = withPoll(service -> {
            throw new YouTubeException(ContentSourceException.Kind.UNAUTHORIZED,
                    "Google rejected the client ID or client secret.");
        });
        refused.start();

        clock.advance(Duration.ofSeconds(5));

        assertThat(refused.pollOnce()).isFalse();
        assertThat(refused.status().state()).isEqualTo(YouTubeAuthorizationService.State.FAILED);
        assertThat(refused.status().message()).isEqualTo("Google rejected the client ID or client secret.");
        clock.advance(Duration.ofSeconds(5));
        assertThat(refused.pollOnce()).as("nothing left to poll").isFalse();
    }

    @Test
    void aFailedResultWithoutADescriptionNamesOnlyTheError() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.json(400, "{\"error\":\"invalid_scope\"}"));
        authorization.start();

        clock.advance(Duration.ofSeconds(5));
        authorization.pollOnce();

        assertThat(authorization.status().message()).isEqualTo("Google refused the authorization (invalid_scope)");
    }

    @Test
    void aPollThatFailsAfterACancelLeavesTheCancel() {
        YouTubeAuthorizationService cancelled = withPoll(service -> {
            service.cancel();
            throw new YouTubeException(ContentSourceException.Kind.UNREACHABLE, "Could not reach Google");
        });
        cancelled.start();

        clock.advance(Duration.ofSeconds(5));

        assertThat(cancelled.pollOnce()).isFalse();
        assertThat(cancelled.status().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
        assertThat(cancelled.status().message()).isNull();
    }

    @Test
    void aPollThatFailsAfterARestartKeepsTheNewCode() {
        AtomicInteger polls = new AtomicInteger();
        YouTubeAuthorizationService restarted = withPoll(service -> {
            if (polls.incrementAndGet() == 1) {
                service.start();
            }
            throw new YouTubeException(ContentSourceException.Kind.UNAUTHORIZED, "refused");
        });
        restarted.start();

        clock.advance(Duration.ofSeconds(5));

        assertThat(restarted.pollOnce()).as("the new code is still pending").isTrue();
        assertThat(restarted.status().state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
        assertThat(restarted.status().message()).isNull();
        assertThat(fake.count("/oauth/device/code")).isEqualTo(2);
    }

    @Test
    void anAnswerForACodeCancelledMeanwhileChangesNothing() {
        YouTubeAuthorizationService cancelled = withPoll(service -> {
            service.cancel();
            return new GoogleOAuthClient.TokenPoll.Denied();
        });
        cancelled.start();

        clock.advance(Duration.ofSeconds(5));

        assertThat(cancelled.pollOnce()).isFalse();
        assertThat(cancelled.status().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
    }

    @Test
    void anAnswerForAReplacedCodeKeepsTheNewCodePending() {
        AtomicInteger polls = new AtomicInteger();
        YouTubeAuthorizationService restarted = withPoll(service -> {
            if (polls.incrementAndGet() == 1) {
                service.start();
            }
            return new GoogleOAuthClient.TokenPoll.Denied();
        });
        restarted.start();

        clock.advance(Duration.ofSeconds(5));

        assertThat(restarted.pollOnce()).isTrue();
        assertThat(restarted.status().state()).isEqualTo(YouTubeAuthorizationService.State.PENDING);
    }

    @Test
    void aCancelWhileTheConnectHookRunsKeepsTheCancel() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        authorization.onConnected(authorization::cancel);
        authorization.start();
        clock.advance(Duration.ofSeconds(5));

        authorization.pollOnce();

        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.IDLE);
        verify(secrets).putSecrets(any());
    }

    @Test
    void aFailingConnectHookStillConnects() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        AtomicInteger laterHooks = new AtomicInteger();
        authorization.onConnected(() -> {
            throw new IllegalStateException("the channel lookup failed");
        });
        authorization.onConnected(laterHooks::incrementAndGet);
        authorization.start();
        clock.advance(Duration.ofSeconds(5));

        authorization.pollOnce();

        assertThat(laterHooks).hasValue(1);
        assertThat(authorization.status().state()).isEqualTo(YouTubeAuthorizationService.State.CONNECTED);
        assertThat(authorization.status().message()).isEqualTo("YouTube connected");
    }

    @Test
    void backgroundPollingCarriesOnAfterAFailedTick() {
        fake.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
        // The first read is start()'s; the second, the first tick's, fails.
        given(secrets.secret(YouTubeSettings.CLIENT_ID)).willReturn(Optional.of("cid"))
                .willThrow(new IllegalStateException("the secrets file cannot be read"))
                .willReturn(Optional.of("cid"));
        GoogleOAuthClient oauth = new GoogleOAuthClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/oauth"), clock);
        try (YouTubeAuthorizationService background = new YouTubeAuthorizationService(oauth, secrets, tokens,
                sourceSettings, clock, true)) {
            background.start();
            clock.advance(Duration.ofSeconds(5));

            await().atMost(Duration.ofSeconds(10)).until(() ->
                    background.status().state() == YouTubeAuthorizationService.State.CONNECTED);
        }
        assertThat(fake.count("/oauth/token")).isEqualTo(1);
    }

    /** A service whose token polls {@code poll} answers; it may call back into the service, as a racing request would. */
    private YouTubeAuthorizationService withPoll(Function<YouTubeAuthorizationService, Object> poll) {
        GoogleOAuthClient oauth = spy(new GoogleOAuthClient(new YouTubeHttp(fake.properties()),
                URI.create(fake.base() + "/oauth"), clock));
        AtomicReference<YouTubeAuthorizationService> service = new AtomicReference<>();
        doAnswer(call -> poll.apply(service.get())).when(oauth).poll(any(), any(), any());
        service.set(new YouTubeAuthorizationService(oauth, secrets, tokens, sourceSettings, clock, false));
        return service.get();
    }
}
