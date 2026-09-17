package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbCredentialTest {

    @Test
    void aThirtyTwoCharacterHexStringIsAnApiKey() {
        TmdbCredential credential = TmdbCredential.parse(" 0123456789abcdef0123456789abcdef ");

        assertThat(credential.kind()).isEqualTo(TmdbCredential.Kind.API_KEY);
        assertThat(credential.value()).isEqualTo("0123456789abcdef0123456789abcdef");
    }

    @Test
    void aJwtIsABearerToken() {
        TmdbCredential plain = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);
        assertThat(plain.kind()).isEqualTo(TmdbCredential.Kind.BEARER);
        assertThat(plain.value()).isEqualTo(FakeTmdbServer.READ_TOKEN);

        TmdbCredential withPrefix = TmdbCredential.parse("Bearer " + FakeTmdbServer.READ_TOKEN);
        assertThat(withPrefix.kind()).isEqualTo(TmdbCredential.Kind.BEARER);
        assertThat(withPrefix.value()).isEqualTo(FakeTmdbServer.READ_TOKEN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "0123456789ABCDEF0123456789ABCDEF", "abc", "a.b.c"})
    void anythingElseIsRejectedWithAHint(String raw) {
        assertThatThrownBy(() -> TmdbCredential.parse(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Paste the API Read Access Token or the API key from your TMDB account settings");
    }

    @ParameterizedTest
    @MethodSource("hugeInput")
    void aHugeThreePartStringIsAlsoRejected(String raw) {
        assertThatThrownBy(() -> TmdbCredential.parse(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Paste the API Read Access Token or the API key from your TMDB account settings");
    }

    static String[] hugeInput() {
        String part = "a".repeat(700);
        return new String[] {part + "." + part + "." + part};
    }

    @Test
    void toStringNeverShowsTheValue() {
        TmdbCredential credential = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);

        assertThat(credential.toString()).contains("BEARER").doesNotContain(FakeTmdbServer.READ_TOKEN);
    }
}
