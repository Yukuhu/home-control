package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceHandleTest {

    /** A connection that lists its inputs and offers nothing else. */
    private static final class ListingHandle implements DeviceHandle, InputListing {

        @Override
        public DeviceState state() {
            return DeviceState.initial();
        }

        @Override
        public void execute(Action action) {
            // Commands are not what these tests look at.
        }

        @Override
        public List<TvInput> inputs() {
            return List.of(new TvInput("HDMI_1", "HDMI 1"));
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }

    @Test
    void aHandleOffersTheFeaturesItImplements() {
        ListingHandle handle = new ListingHandle();

        assertThat(handle.feature(InputListing.class)).containsSame(handle);
    }

    @Test
    void aHandleWithoutAFeatureOffersNone() {
        ListingHandle handle = new ListingHandle();

        assertThat(handle.feature(GroupListing.class)).isEmpty();
        assertThat(handle.feature(ReceiverApps.class)).isEmpty();
    }
}
