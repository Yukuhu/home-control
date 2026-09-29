package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static dev.andre.homecontrol.testsupport.Planners.unroutableReason;
import static org.assertj.core.api.Assertions.assertThat;

/** What a person reads when nothing routes a YouTube Lounge reference. */
class YouTubeLoungeRefTest {

    @Test
    void aLoungeReferenceNeedsACastReceiver() {
        assertThat(unroutableReason(new YouTubeLoungeRef("abc"), EnumSet.noneOf(Capability.class)))
                .isEqualTo("this device is not a Cast receiver");
    }
}
