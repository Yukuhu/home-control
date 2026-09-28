package dev.andre.homecontrol.sources.tmdb;

import java.time.Duration;
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
        runner.withPropertyValues("home-control.tmdb.connect-timeout=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("connectTimeout"));
    }

    @Test
    void aNonPositiveRequestTimeoutFailsAtStartup() {
        runner.withPropertyValues("home-control.tmdb.request-timeout=-1s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("requestTimeout"));
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

    @Test
    void aBareNumberMeansSeconds() {
        runner.withPropertyValues("home-control.tmdb.request-timeout=12")
                .run(context -> assertThat(context.getBean(TmdbProperties.class).requestTimeout())
                        .isEqualTo(Duration.ofSeconds(12)));
    }
}
