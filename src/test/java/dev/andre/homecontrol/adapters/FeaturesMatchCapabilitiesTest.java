package dev.andre.homecontrol.adapters;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvSession;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerAdapter;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerSession;
import dev.andre.homecontrol.adapters.cast.CastAdapter;
import dev.andre.homecontrol.adapters.cast.CastSession;
import dev.andre.homecontrol.adapters.sonos.SonosAdapter;
import dev.andre.homecontrol.adapters.sonos.SonosSession;
import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.tizen.TizenSession;
import dev.andre.homecontrol.adapters.upnp.UpnpAdapter;
import dev.andre.homecontrol.adapters.upnp.UpnpSession;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsSession;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.GroupListing;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.ReceiverApps;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * A session offers a feature exactly when its adapter declares the capability that goes with it. Otherwise the
 * dashboard offers input buttons that fail with "cannot switch inputs", or a declared capability offers nothing.
 */
class FeaturesMatchCapabilitiesTest {

    private static final Map<Class<?>, Capability> FEATURES = Map.of(
            InputListing.class, Capability.INPUTS,
            GroupListing.class, Capability.GROUPING,
            ReceiverApps.class, Capability.CAST_RECEIVER);

    static Stream<Arguments> adapters() {
        return Stream.of(
                arguments(AndroidTvAdapter.class, AndroidTvSession.class),
                arguments(WebOsAdapter.class, WebOsSession.class),
                arguments(TizenAdapter.class, TizenSession.class),
                arguments(CastAdapter.class, CastSession.class),
                arguments(SonosAdapter.class, SonosSession.class),
                arguments(UpnpAdapter.class, UpnpSession.class),
                arguments(BluetoothSpeakerAdapter.class, BluetoothSpeakerSession.class));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("adapters")
    void aSessionOffersAFeatureExactlyWhenItsAdapterDeclaresItsCapability(
            Class<? extends DeviceAdapter> adapterType, Class<? extends DeviceHandle> sessionType) {
        // The capabilities are constant sets; the real method answers without the adapter's collaborators.
        Set<Capability> declared = mock(adapterType, withSettings().defaultAnswer(CALLS_REAL_METHODS))
                .capabilities(null);

        FEATURES.forEach((feature, capability) -> assertThat(feature.isAssignableFrom(sessionType))
                .as("%s offers %s", sessionType.getSimpleName(), feature.getSimpleName())
                .isEqualTo(declared.contains(capability)));
    }
}
