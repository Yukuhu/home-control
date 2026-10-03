package dev.andre.homecontrol.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * {@code home-control.security.*}. The secret never appears in {@link #toString()}. {@code resetLogin} removes a
 * forgotten login password, and the content sources' credentials it protects, at startup.
 */
@ConfigurationProperties("home-control.security")
@Validated
public record SecurityProperties(String secret,
                                 List<String> trustedOrigins,
                                 @DefaultValue("5") @Positive int loginAttemptsPerAddress,
                                 @DefaultValue("50") @Positive int loginAttemptsTotal,
                                 @DefaultValue("15m") @NotNull Duration loginWindow,
                                 List<String> allowedHosts,
                                 @DefaultValue("false") boolean resetLogin,
                                 List<String> trustedProxies) {

    public SecurityProperties {
        if (loginWindow != null && (loginWindow.isNegative() || loginWindow.isZero())) {
            throw new IllegalArgumentException("home-control.security.login-window must be positive");
        }
        trustedProxies = trustedProxies == null ? List.of()
                : trustedProxies.stream().map(String::strip).filter(proxy -> !proxy.isEmpty()).toList();
        for (String proxy : trustedProxies) {
            try {
                InetAddress.ofLiteral(proxy);
            } catch (IllegalArgumentException _) {
                throw new IllegalArgumentException("home-control.security.trusted-proxies lists IP addresses, which \""
                        + proxy + "\" is not");
            }
        }
    }

    @Override
    public String toString() {
        return "SecurityProperties[secret=" + (secret == null || secret.isBlank() ? "unset" : "set")
                + ", trustedOrigins=" + trustedOrigins + ", loginAttemptsPerAddress=" + loginAttemptsPerAddress
                + ", loginAttemptsTotal=" + loginAttemptsTotal + ", loginWindow=" + loginWindow
                + ", allowedHosts=" + allowedHosts + ", resetLogin=" + resetLogin
                + ", trustedProxies=" + trustedProxies + "]";
    }
}
