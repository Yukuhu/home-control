package dev.andre.homecontrol.sources.pinned;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PinnedPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PinnedProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void theDefaultsAreValid() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .getBean(PinnedProperties.class).extracting(PinnedProperties::maxPins).isEqualTo(200));
    }

    @Test
    void aNonPositiveMaxPinsFailsAtStartup() {
        runner.withPropertyValues("home-control.pinned.max-pins=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("maxPins"));
    }
}
