package dev.andre.homecontrol.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** {@code home-control.security.*}. The secret never appears in {@link #toString()}. */
@ConfigurationProperties("home-control.security")
@Validated
public record SecurityProperties(String secret,
                                 List<String> trustedOrigins,
                                 @DefaultValue("5") @Positive int loginAttemptsPerAddress,
                                 @DefaultValue("50") @Positive int loginAttemptsTotal,
                                 @DefaultValue("15m") @NotNull Duration loginWindow,
                                 List<String> allowedHosts) {

    public SecurityProperties {
        if (loginWindow != null && (loginWindow.isNegative() || loginWindow.isZero())) {
            throw new IllegalArgumentException("home-control.security.login-window must be positive");
        }
    }

    @Override
    public String toString() {
        return "SecurityProperties[secret=" + (secret == null || secret.isBlank() ? "unset" : "set")
                + ", trustedOrigins=" + trustedOrigins + ", loginAttemptsPerAddress=" + loginAttemptsPerAddress
                + ", loginAttemptsTotal=" + loginAttemptsTotal + ", loginWindow=" + loginWindow
                + ", allowedHosts=" + allowedHosts + "]";
    }
}
