package dev.andre.homecontrol.sources.workflows;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("home-control.workflows")
public record WorkflowProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("false") boolean allowLoopback,
                                 @DefaultValue("5s") Duration connectTimeout,
                                 @DefaultValue("10s") Duration requestTimeout,
                                 @DefaultValue("8") int maxConcurrentFetches,
                                 @DefaultValue("2097152") int maxBytes,
                                 @DefaultValue("3") int maxRedirects,
                                 @DefaultValue("20s") Duration playTimeout,
                                 @DefaultValue("60s") Duration refreshTimeout) {
    @ConstructorBinding
    public WorkflowProperties {
        validateTimeout(connectTimeout);
        validateTimeout(requestTimeout);
        validateTimeout(playTimeout);
        validateTimeout(refreshTimeout);
        if (maxConcurrentFetches <= 0 || maxBytes <= 0 || maxBytes == Integer.MAX_VALUE || maxRedirects <= 0) {
            throw new IllegalArgumentException("Workflow limits must be positive and finite");
        }
    }

    /** The settings without run deadlines, which take their defaults. */
    public WorkflowProperties(boolean enabled, boolean allowLoopback, Duration connectTimeout, Duration requestTimeout,
                              int maxConcurrentFetches, int maxBytes, int maxRedirects) {
        this(enabled, allowLoopback, connectTimeout, requestTimeout, maxConcurrentFetches, maxBytes, maxRedirects,
                Duration.ofSeconds(20), Duration.ofSeconds(60));
    }

    private static void validateTimeout(Duration value) {
        try {
            if (value == null || value.isNegative() || value.isZero() || value.toNanos() <= 0) {
                throw new IllegalArgumentException("Workflow timeouts must be positive and finite");
            }
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException("Workflow timeouts must be positive and finite");
        }
    }
}
