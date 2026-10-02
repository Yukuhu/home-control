package dev.andre.homecontrol.adapters.androidtv;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidTvPropertiesTest {

    @EnableConfigurationProperties(AndroidTvProperties.class)
    static class Bound {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Bound.class);

    @Test
    void theDefaultsAreValid() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .getBean(AndroidTvProperties.class).extracting(AndroidTvProperties::reconnectInitialDelay)
                .isEqualTo(Duration.ofSeconds(1)));
    }

    /** A zero delay would reconnect to an unreachable Shield in a tight loop. */
    @Test
    void aZeroReconnectDelayFailsAtStartup() {
        runner.withPropertyValues("home-control.androidtv.reconnect-initial-delay=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("reconnectInitialDelay"));
    }

    /** A zero stale timeout would never notice a dead connection. */
    @Test
    void aZeroStaleTimeoutFailsAtStartup() {
        runner.withPropertyValues("home-control.androidtv.stale-timeout=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("staleTimeout"));
    }

    @Test
    void aMaxDelayBelowTheInitialDelayFailsAtStartup() {
        runner.withPropertyValues("home-control.androidtv.reconnect-initial-delay=10s",
                        "home-control.androidtv.reconnect-max-delay=5s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessage("home-control.androidtv.reconnect-max-delay"
                                + " (5 seconds) must not be less than"
                                + " home-control.androidtv.reconnect-initial-delay (10 seconds)"));
    }
}
