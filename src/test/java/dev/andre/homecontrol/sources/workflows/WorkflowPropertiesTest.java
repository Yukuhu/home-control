package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class WorkflowPropertiesTest {

    @Test void propertiesRejectUnboundedOrNonPositiveLimits() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        var unbounded = Duration.ofSeconds(Long.MAX_VALUE);
        assertThatThrownBy(() -> properties(unbounded, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        var oneSecond = Duration.ofSeconds(1);
        assertThatThrownBy(() -> properties(oneSecond, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(oneSecond, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(oneSecond, 1, Integer.MAX_VALUE, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(oneSecond, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void bindsSafeDefaultsAndAllowsExplicitLocalOverrides() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("home-control.workflows.enabled", "true"));
        var binder = new org.springframework.boot.context.properties.bind.Binder(source);
        var defaults = binder.bind("home-control.workflows", WorkflowProperties.class).get();
        assertThat(defaults).isEqualTo(new WorkflowProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(10), 8, 2_097_152, 3));
        assertThat(defaults.playTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.refreshTimeout()).isEqualTo(Duration.ofSeconds(60));
        source.put("home-control.workflows.allow-loopback", "true");
        source.put("home-control.workflows.request-timeout", "250ms");
        var configured = binder.bind("home-control.workflows", WorkflowProperties.class).get();
        assertThat(configured.allowLoopback()).isTrue();
        assertThat(configured.requestTimeout()).isEqualTo(Duration.ofMillis(250));
    }

    private WorkflowProperties properties(Duration timeout, int concurrent, int bytes, int redirects) {
        return new WorkflowProperties(true, false, timeout, timeout, concurrent, bytes, redirects);
    }
}
