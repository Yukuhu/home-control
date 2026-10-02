package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WakeOnLanConfigurationTest {

    private static final String MAC = "AA:BB:CC:DD:EE:FF";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WakeOnLanConfiguration.class);

    @Test
    void theSenderWakesAtTheConfiguredAddressAndPort() throws Exception {
        try (FakeWakeOnLanReceiver receiver = new FakeWakeOnLanReceiver()) {
            context.withPropertyValues(
                            "home-control.wake-on-lan.broadcast-address=" + receiver.address().getHostString(),
                            "home-control.wake-on-lan.port=" + receiver.port())
                    .run(started -> {
                        started.getBean(WakeOnLan.class).wake(MAC);

                        assertThat(receiver.nextPacket()).isEqualTo(WakeOnLan.magicPacket(MAC));
                    });
        }
    }

    /** Fails for the port itself, named, not for whatever else could stop the context starting. */
    @Test
    void aPortOutsideTheRangeFailsStartup() {
        context.withPropertyValues("home-control.wake-on-lan.port=0")
                .run(started -> assertThat(started).hasFailed().getFailure().rootCause()
                        .isInstanceOf(BindValidationException.class)
                        .hasMessageContaining("home-control.wake-on-lan")
                        .hasMessageContaining("'port'"));
    }
}
