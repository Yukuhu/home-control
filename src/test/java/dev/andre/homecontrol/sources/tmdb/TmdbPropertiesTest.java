package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TmdbProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void theDefaultsAreValid() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .getBean(TmdbProperties.class).extracting(TmdbProperties::railSize).isEqualTo(20));
    }

    @Test
    void aNonPositiveConnectTimeoutFailsAtStartup() {
        runner.withPropertyValues("home-control.tmdb.connect-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("connectTimeoutSeconds"));
    }

    @Test
    void aNonPositiveRequestTimeoutFailsAtStartup() {
        runner.withPropertyValues("home-control.tmdb.request-timeout-seconds=-1")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("requestTimeoutSeconds"));
    }

    @Test
    void aNonPositiveRailSizeFailsAtStartup() {
        runner.withPropertyValues("home-control.tmdb.rail-size=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("railSize"));
    }

    @Test
    void aNonPositiveTrendingCandidatesFailsAtStartup() {
        runner.withPropertyValues("home-control.tmdb.trending-candidates=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("trendingCandidates"));
    }
}
