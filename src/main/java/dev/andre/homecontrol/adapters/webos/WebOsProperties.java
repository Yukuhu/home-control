package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.support.DurationText;
import dev.andre.homecontrol.adapters.webos.protocol.SsapOptions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.webos.*}. {@code wakeGrace}: after a Wake-on-LAN packet, how long the
 * TV gets before the first reconnect. {@code livenessInterval}: how often a connected TV is
 * asked a cheap question; no answer within the request timeout means the connection is lost.
 * A bad value fails startup instead of surfacing later as a busy reconnect loop.
 */
@ConfigurationProperties("home-control.webos")
@Validated
public record WebOsProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("3000") @Min(1) @Max(65535) int port,
                              @DefaultValue("3001") @Min(1) @Max(65535) int securePort,
                              @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration connectTimeout,
                              @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration requestTimeout,
                              @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration pairingTimeout,
                              @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration reconnectInitialDelay,
                              @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration reconnectMaxDelay,
                              @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 0)
                              Duration wakeGrace,
                              @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration livenessInterval) {

    @ConstructorBinding
    public WebOsProperties {
        if (reconnectMaxDelay != null && reconnectInitialDelay != null
                && reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalArgumentException("home-control.webos.reconnect-max-delay ("
                    + DurationText.of(reconnectMaxDelay) + ") must not be less than"
                    + " home-control.webos.reconnect-initial-delay (" + DurationText.of(reconnectInitialDelay) + ")");
        }
    }

    /** Everything but the liveness interval, which then is the 30-second default. */
    public WebOsProperties(boolean enabled, int port, int securePort, Duration connectTimeout, Duration requestTimeout,
                           Duration pairingTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay,
                           Duration wakeGrace) {
        this(enabled, port, securePort, connectTimeout, requestTimeout, pairingTimeout,
                reconnectInitialDelay, reconnectMaxDelay, wakeGrace, Duration.ofSeconds(30));
    }

    /** The SSAP connection's ports and waits. */
    public SsapOptions ssap() {
        return new SsapOptions(port, securePort, connectTimeout, requestTimeout);
    }
}
