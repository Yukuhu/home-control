package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.andre.homecontrol.storage.SecretStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GoogleTokensTest {

    private GoogleOAuthClient oauth;
    private SecretStore secrets;
    private MutableClock clock;
    private GoogleTokens tokens;

    @BeforeEach
    void setUp() {
        oauth = mock(GoogleOAuthClient.class);
        secrets = mock(SecretStore.class);
        given(secrets.secret(YouTubeSettings.CLIENT_ID)).willReturn(Optional.of("cid"));
        given(secrets.secret(YouTubeSettings.CLIENT_SECRET)).willReturn(Optional.of("csecret"));
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.of("rt"));
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        tokens = new GoogleTokens(oauth, secrets, clock);
    }

    @Test
    void refreshesOnceAndCaches() {
        given(oauth.refresh("cid", "csecret", "rt"))
                .willReturn(new GoogleOAuthClient.AccessToken("ya29.a", clock.instant().plus(Duration.ofHours(1))));

        assertThat(tokens.accessToken()).isEqualTo("ya29.a");
        assertThat(tokens.accessToken()).isEqualTo("ya29.a");

        verify(oauth, times(1)).refresh("cid", "csecret", "rt");
    }

    @Test
    void refreshesAMinuteBeforeExpiry() {
        given(oauth.refresh("cid", "csecret", "rt"))
                .willReturn(new GoogleOAuthClient.AccessToken("ya29.a", clock.instant().plus(Duration.ofMinutes(10))));

        tokens.accessToken();
        clock.advance(Duration.ofMinutes(9).plusSeconds(1));
        given(oauth.refresh("cid", "csecret", "rt"))
                .willReturn(new GoogleOAuthClient.AccessToken("ya29.b", clock.instant().plus(Duration.ofHours(1))));
        tokens.accessToken();

        verify(oauth, times(2)).refresh("cid", "csecret", "rt");
    }

    @Test
    void invalidateForcesARefresh() {
        given(oauth.refresh("cid", "csecret", "rt"))
                .willReturn(new GoogleOAuthClient.AccessToken("ya29.a", clock.instant().plus(Duration.ofHours(1))));

        tokens.accessToken();
        tokens.invalidate();
        tokens.accessToken();

        verify(oauth, times(2)).refresh("cid", "csecret", "rt");
    }

    @Test
    void withoutClientOrRefreshTokenItIsNotConfigured() {
        given(secrets.secret(YouTubeSettings.REFRESH_TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> tokens.accessToken())
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.NOT_CONFIGURED);
        assertThatThrownBy(() -> tokens.accessToken()).hasMessage("YouTube is not connected");
    }

    @Test
    void aRevokedGrantIsRememberedUntilReset() {
        YouTubeException revoked = new YouTubeException(YouTubeException.Kind.REVOKED, "Google no longer accepts...");
        given(oauth.refresh("cid", "csecret", "rt")).willThrow(revoked);

        assertThatThrownBy(() -> tokens.accessToken()).isSameAs(revoked);
        assertThatThrownBy(() -> tokens.accessToken()).isSameAs(revoked);
        verify(oauth, times(1)).refresh(any(), any(), any());
        assertThat(tokens.revoked()).isTrue();

        tokens.reset();
        Mockito.doReturn(new GoogleOAuthClient.AccessToken("ya29.a", clock.instant().plus(Duration.ofHours(1))))
                .when(oauth).refresh("cid", "csecret", "rt");
        assertThat(tokens.accessToken()).isEqualTo("ya29.a");
        assertThat(tokens.revoked()).isFalse();
    }

    @Test
    void primeAvoidsARefresh() {
        tokens.prime(new GoogleOAuthClient.AccessToken("ya29.x", clock.instant().plus(Duration.ofHours(1))));

        assertThat(tokens.accessToken()).isEqualTo("ya29.x");
        verify(oauth, never()).refresh(any(), any(), any());
    }
}
