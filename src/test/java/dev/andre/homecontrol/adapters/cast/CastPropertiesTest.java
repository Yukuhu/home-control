package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.discovery.MdnsBrowser;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MdnsBrowser.class, () -> new MdnsBrowser(false))
            .withUserConfiguration(CastConfiguration.class);

    @Test
    void theDefaultsAreValid() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .getBean(CastProperties.class).extracting(CastProperties::staleTimeout)
                .isEqualTo(Duration.ofSeconds(15)));
    }

    @Test
    void aNonPositiveDurationFailsAtStartup() {
        runner.withPropertyValues("home-control.cast.command-timeout=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("commandTimeout"));
    }

    @Test
    void aStaleTimeoutNotAboveTheHeartbeatIntervalFailsAtStartup() {
        runner.withPropertyValues("home-control.cast.heartbeat-interval=15s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("stale-timeout"));
    }

    @Test
    void theRecordItselfRejectsAStaleTimeoutNotAboveTheHeartbeat() {
        Duration heartbeatInterval = Duration.ofSeconds(5);
        Duration reconnectInitialDelay = Duration.ofSeconds(1);
        Duration reconnectMaxDelay = Duration.ofSeconds(60);
        Duration loadTimeout = Duration.ofSeconds(20);

        assertThatThrownBy(() -> new CastProperties(true, heartbeatInterval, heartbeatInterval,
                reconnectInitialDelay, reconnectMaxDelay, heartbeatInterval, loadTimeout, heartbeatInterval))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("home-control.cast.stale-timeout (5 seconds) must be greater than"
                        + " home-control.cast.heartbeat-interval (5 seconds)");
    }

    @Test
    void aBareNumberMeansSeconds() {
        runner.withPropertyValues("home-control.cast.stale-timeout=20")
                .run(context -> assertThat(context.getBean(CastProperties.class).staleTimeout())
                        .isEqualTo(Duration.ofSeconds(20)));
    }
}
