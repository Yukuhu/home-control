package dev.andre.homecontrol.sources.workflows;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("home-control.workflows")
public record WorkflowProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("false") boolean allowLoopback,
                                 @DefaultValue("5s") Duration connectTimeout,
                                 @DefaultValue("15s") Duration requestTimeout,
                                 @DefaultValue("4") int maxConcurrentFetches,
                                 @DefaultValue("2097152") int maxBytes,
                                 @DefaultValue("3") int maxRedirects) {
    public WorkflowProperties {
        validateTimeout(connectTimeout);
        validateTimeout(requestTimeout);
        if (maxConcurrentFetches <= 0 || maxBytes <= 0 || maxBytes == Integer.MAX_VALUE || maxRedirects <= 0) {
            throw new IllegalArgumentException("Workflow limits must be positive and finite");
        }
    }

    private static void validateTimeout(Duration value) {
        try {
            if (value == null || value.isNegative() || value.isZero() || value.toNanos() <= 0) {
                throw new IllegalArgumentException("Workflow timeouts must be positive and finite");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Workflow timeouts must be positive and finite");
        }
    }
}
