package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.discovery.MdnsBrowser;
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
                .getBean(CastProperties.class).extracting(CastProperties::staleTimeoutSeconds).isEqualTo(15));
    }

    @Test
    void aNonPositiveDurationFailsAtStartup() {
        runner.withPropertyValues("home-control.cast.command-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("commandTimeoutSeconds"));
    }

    @Test
    void aStaleTimeoutNotAboveTheHeartbeatIntervalFailsAtStartup() {
        runner.withPropertyValues("home-control.cast.heartbeat-interval-seconds=15")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("stale-timeout-seconds"));
    }

    @Test
    void theRecordItselfRejectsAStaleTimeoutNotAboveTheHeartbeat() {
        assertThatThrownBy(() -> new CastProperties(true, 5, 5, 1, 60, 5, 20, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be greater than");
    }
}
