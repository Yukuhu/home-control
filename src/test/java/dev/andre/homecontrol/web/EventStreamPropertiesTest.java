package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code home-control.events.heartbeat-interval}: 25 s unless set; it cannot be switched off. */
class EventStreamPropertiesTest {

    @EnableConfigurationProperties(EventStreamProperties.class)
    static class Enabled {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Enabled.class);

    @Test
    void theHeartbeatComesEvery25SecondsByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed().getBean(EventStreamProperties.class)
                .extracting(EventStreamProperties::heartbeatInterval).isEqualTo(Duration.ofSeconds(25)));
    }

    @Test
    void theIntervalCanBeSet() {
        runner.withPropertyValues("home-control.events.heartbeat-interval=10s")
                .run(context -> assertThat(context).hasNotFailed().getBean(EventStreamProperties.class)
                        .extracting(EventStreamProperties::heartbeatInterval).isEqualTo(Duration.ofSeconds(10)));
    }

    @Test
    void anIntervalUnderASecondFailsAtStartupNamingTheSetting() {
        runner.withPropertyValues("home-control.events.heartbeat-interval=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("heartbeatInterval"));
    }
}
