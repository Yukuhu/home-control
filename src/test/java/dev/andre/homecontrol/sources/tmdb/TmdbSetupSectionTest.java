package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.security.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** What the setup page shows about TMDB: whether it is connected, with which kind of credential. */
class TmdbSetupSectionTest {

    private static final URI HOME = URI.create("http://home-control.lan:8080");

    private final TmdbSetupService setup = mock(TmdbSetupService.class);
    private final LoginService login = mock(LoginService.class);

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(bean);
        return provider;
    }

    @Test
    void withoutTheTmdbBeansTheConnectFormAsksForAPassword() {
        assertThat(new TmdbSetupSection(provider(null), provider(null)).view(HOME))
                .isEqualTo(new TmdbSetupSection.View(false, null, true));
    }

    @Test
    void anUnconnectedTmdbOffersTheForm() {
        given(setup.settings()).willReturn(Optional.empty());
        given(login.loginRequired()).willReturn(true);

        assertThat(new TmdbSetupSection(provider(setup), provider(login)).view(HOME))
                .isEqualTo(new TmdbSetupSection.View(false, null, false));
    }

    @Test
    void aConnectedTmdbNamesItsKindOfCredential() {
        var section = new TmdbSetupSection(provider(setup), provider(login));

        given(setup.settings()).willReturn(Optional.of(new TmdbSettings(TmdbCredential.Kind.BEARER, Instant.EPOCH)));
        assertThat(section.view(HOME)).isEqualTo(new TmdbSetupSection.View(true, "read access token", true));

        given(setup.settings()).willReturn(Optional.of(new TmdbSettings(TmdbCredential.Kind.API_KEY, Instant.EPOCH)));
        assertThat(section.view(HOME).credentialKind()).isEqualTo("API key");
    }
}
