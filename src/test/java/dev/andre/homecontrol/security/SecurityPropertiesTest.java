package dev.andre.homecontrol.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesTest {

    private static final List<String> NONE = List.of();
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static SecurityProperties withSecret(String secret) {
        return new SecurityProperties(secret, NONE, 5, 50, WINDOW, NONE, false, NONE);
    }

    @Test
    void trustedProxiesAreAddressesWithBlanksLeftOut() {
        SecurityProperties properties = new SecurityProperties(null, NONE, 5, 50, WINDOW, NONE, false,
                List.of(" 10.0.0.1 ", "", "::1"));

        assertThat(properties.trustedProxies()).containsExactly("10.0.0.1", "::1");
        assertThat(new SecurityProperties(null, NONE, 5, 50, WINDOW, NONE, false, null).trustedProxies()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aLoginWindowThatIsNotPositiveIsRefused(long minutes) {
        Duration window = Duration.ofMinutes(minutes);

        assertThatThrownBy(() -> new SecurityProperties(null, NONE, 5, 50, window, NONE, false, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("home-control.security.login-window must be positive");
    }

    @Test
    void theSecretNeverAppearsWhenPrinted() {
        assertThat(withSecret("s3cr3t-value").toString()).contains("secret=set").doesNotContain("s3cr3t-value");
        assertThat(withSecret(" ").toString()).contains("secret=unset");
        assertThat(withSecret(null).toString()).contains("secret=unset").contains("loginWindow=PT15M");
    }
}
