package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingStateListenerTest {

    private final RecordingStateListener states = new RecordingStateListener();

    private static DeviceState state(DeviceStatus status) {
        return DeviceState.initial().withStatus(status);
    }

    @Test
    void recordsEveryStateInOrder() {
        states.accept(state(DeviceStatus.CONNECTING));
        states.accept(state(DeviceStatus.CONNECTED));

        assertThat(states.all()).extracting(DeviceState::status)
                .containsExactly(DeviceStatus.CONNECTING, DeviceStatus.CONNECTED);
        assertThat(states.last().status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void lastFailsBeforeTheFirstStateAndClearForgets() {
        assertThatThrownBy(states::last).isInstanceOf(NoSuchElementException.class);

        states.accept(state(DeviceStatus.CONNECTED));
        states.clear();

        assertThat(states.all()).isEmpty();
    }

    @Test
    void awaitStatusReturnsTheFirstStateWithThatStatus() {
        states.accept(state(DeviceStatus.CONNECTING));
        Thread.ofVirtual().start(() -> states.accept(state(DeviceStatus.DISCONNECTED)));

        assertThat(states.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(5)).status())
                .isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void awaitStatusFailsWhenTheStatusNeverComes() {
        states.accept(state(DeviceStatus.CONNECTED));

        assertThatThrownBy(() -> states.awaitStatus(DeviceStatus.UNPAIRED, Duration.ofMillis(200)))
                .isInstanceOf(ConditionTimeoutException.class);
    }
}
