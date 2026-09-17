package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
class DashboardPageTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    RailCache rails;

    @MockitoBean
    ContentSources sources;

    private static Device device(String id, String name, Instant lastSeen) {
        return new Device(id, name, DeviceKind.ANDROID_TV, "10.0.0." + id.length(),
                Map.of("androidtv", Map.of()), lastSeen);
    }

    @Test
    void redirectsToSetupWhenNothingIsPaired() throws Exception {
        given(devices.devices()).willReturn(List.of());

        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup"));
    }

    @Test
    void showsEveryDeviceAndTheRemoteForTheSelectedOne() throws Exception {
        Device living = device("living", "Living Room", Instant.parse("2026-09-01T00:00:00Z"));
        Device bedroom = device("bedroom", "Bedroom", Instant.parse("2026-09-02T00:00:00Z"));
        given(devices.devices()).willReturn(List.of(bedroom, living));
        given(devices.device("living")).willReturn(Optional.of(living));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.state("living")).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.now()));
        given(devices.state("bedroom")).willReturn(DeviceState.unpaired());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/").param("device", "living"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"status-living\"")))
                .andExpect(content().string(containsString("id=\"status-bedroom\"")))
                .andExpect(content().string(containsString("/devices/living/key/DPAD_UP")))
                .andExpect(content().string(not(containsString("/devices/bedroom/key/"))))
                .andExpect(content().string(containsString("com.netflix.ninja")))
                .andExpect(content().string(containsString("/devices/living/play")))
                .andExpect(content().string(containsString("<body data-device=\"living\"")))
                .andExpect(content().string(not(containsString("/devices/living/volume"))));
    }

    @Test
    void fallsBackToTheDefaultDeviceWhenNoneIsSelected() throws Exception {
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        given(devices.devices()).willReturn(List.of(bedroom));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.device("bedroom")).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/devices/bedroom/key/HOME")))
                .andExpect(content().string(not(containsString("/devices/bedroom/play"))));
    }

    @Test
    void theDefaultDeviceIsPreferredOverTheFirstDeviceInTheList() throws Exception {
        Device living = device("living", "Living Room", Instant.now());
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        // Living is first in the list DeviceManager returns, but the default is bedroom:
        // the selection must come from defaultDevice(), not from all.getFirst().
        given(devices.devices()).willReturn(List.of(living, bedroom));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<body data-device=\"bedroom\"")))
                .andExpect(content().string(containsString("/devices/bedroom/key/HOME")))
                .andExpect(content().string(not(containsString("/devices/living/key/"))));
    }

    @Test
    void anUnknownSelectionFallsBackToTheDefaultDevice() throws Exception {
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        given(devices.devices()).willReturn(List.of(bedroom));
        given(devices.device("ghost")).willReturn(Optional.empty());
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/").param("device", "ghost"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/devices/bedroom/key/HOME")));
    }

    @Test
    void theClassicRemotePathRedirectsToTheDashboard() throws Exception {
        mockMvc.perform(get("/remote/living"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?device=living&remote=open"));
    }

    @Test
    void aCastOnlyDeviceGetsCastControlsInsteadOfTheRemote() throws Exception {
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8009")), Instant.now());
        given(devices.devices()).willReturn(List.of(kitchen));
        given(devices.defaultDevice()).willReturn(Optional.of(kitchen));
        given(devices.device("cast-10-0-0-9")).willReturn(Optional.of(kitchen));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/volume")))
                .andExpect(content().string(containsString("id=\"volume-cast-10-0-0-9\"")))
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/mute")))
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/stop")))
                .andExpect(content().string(not(containsString("/devices/cast-10-0-0-9/key/"))));
    }

    @Test
    void theVolumeSliderIsScaledToTheDevicesOwnVolumeRange() throws Exception {
        // A Shield merged with its Cast entry: Android TV's own volume steps (max 15) are the
        // composed volumeMax, but the slider is always 0-100 (what SetVolume takes).
        Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of(), "cast", Map.of("port", "8009")), Instant.now());
        given(devices.devices()).willReturn(List.of(shield));
        given(devices.defaultDevice()).willReturn(Optional.of(shield));
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.state(any())).willReturn(
                new DeviceState(DeviceStatus.CONNECTED, true, "app", 6, 15, false, Instant.now()));
        given(devices.capabilities(any()))
                .willReturn(EnumSet.of(Capability.REMOTE_KEYS, Capability.CAST_RECEIVER, Capability.VOLUME));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"volume-shield\"")))
                .andExpect(content().string(containsString("value=\"40\"")));
    }

    @Test
    void aVolumeMaxOfZeroLeavesTheSliderAtZeroInsteadOfDividingByZero() throws Exception {
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8009")), Instant.now());
        given(devices.devices()).willReturn(List.of(kitchen));
        given(devices.defaultDevice()).willReturn(Optional.of(kitchen));
        given(devices.device("cast-10-0-0-9")).willReturn(Optional.of(kitchen));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"volume-cast-10-0-0-9\"")))
                .andExpect(content().string(containsString("value=\"0\"")));
    }

    @Test
    void aCastOnlyDeviceOffersTheLinkForm() throws Exception {
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8009")), Instant.now());
        given(devices.devices()).willReturn(List.of(kitchen));
        given(devices.defaultDevice()).willReturn(Optional.of(kitchen));
        given(devices.device("cast-10-0-0-9")).willReturn(Optional.of(kitchen));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/play")))
                .andExpect(content().string(containsString("Direct media links")));
    }

    @Test
    void theChipShowsWhatIsPlaying() throws Exception {
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of()), Instant.now());
        given(devices.devices()).willReturn(List.of(kitchen));
        given(devices.defaultDevice()).willReturn(Optional.of(kitchen));
        given(devices.device("cast-10-0-0-9")).willReturn(Optional.of(kitchen));
        given(devices.state("cast-10-0-0-9")).willReturn(new DeviceState(DeviceStatus.CONNECTED, true,
                "Default Media Receiver", 30, 100, false, Instant.now(),
                new NowPlaying("Big Buck Bunny", PlaybackState.PLAYING, 12.5, 596.5)));
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("Big Buck Bunny")))
                .andExpect(content().string(not(containsString(">Default Media Receiver<"))));
    }

    @Test
    void showsRailsFromTheCacheWithoutCallingSources() throws Exception {
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        given(devices.devices()).willReturn(List.of(bedroom));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.device("bedroom")).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));
        ContentSource source = org.mockito.Mockito.mock(ContentSource.class);
        given(source.displayName()).willReturn("Jellyfin");
        given(sources.all()).willReturn(List.of(source));
        given(sources.find("jellyfin")).willReturn(Optional.of(source));
        RailSnapshot snapshot = new RailSnapshot(new RailDescriptor("jellyfin", "resume", "Continue watching"),
                RailStatus.READY, List.of(), Instant.now(), null, false, 1);
        given(rails.snapshots()).willReturn(List.of(snapshot));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"rails\"")))
                .andExpect(content().string(containsString("id=\"rail-jellyfin-resume\"")));

        verify(source, never()).rail(any());
    }

    @Test
    void theRemoteIsADrawerClosedByDefault() throws Exception {
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        given(devices.devices()).willReturn(List.of(bedroom));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.device("bedroom")).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"remote-drawer\"")))
                .andExpect(content().string(containsString("hidden=\"hidden\"")))
                .andExpect(content().string(containsString("/devices/bedroom/key/DPAD_UP")));

        mockMvc.perform(get("/").param("remote", "open"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("hidden=\"hidden\""))));
    }

    @Test
    void pointsToSetupWhenNoSourceIsConfigured() throws Exception {
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        given(devices.devices()).willReturn(List.of(bedroom));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.device("bedroom")).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));
        given(sources.all()).willReturn(List.of());
        given(rails.snapshots()).willReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Connect a source")));
    }
}
