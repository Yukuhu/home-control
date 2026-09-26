package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.sonos.FakeSonosHousehold;
import dev.andre.homecontrol.adapters.sonos.FakeSonosPlayer;
import dev.andre.homecontrol.adapters.sonos.SonosDiscovery;
import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.adapters.upnp.UpnpDiscovery;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A DLNA renderer and a two-room Sonos household through the whole application over real sockets:
 * SSDP discovery, adding from the setup page, SOAP playback, volume, now playing, reconnect on
 * announcement, grouping and forgetting.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpeakersEndToEndTest {

    static final String LIVING = "RINCON_000E58A0B1C201400";
    static final String KITCHEN = "RINCON_000E58C3D4E501400";
    static final String RENDERER_ID = "upnp-127-0-0-1";
    static final String LIVING_ID = "sonos-127-0-0-2";
    static final String KITCHEN_ID = "sonos-127-0-0-3";
    static final Duration WAIT = Duration.ofSeconds(10);
    static final FakeSsdpResponder SSDP;
    static final FakeUpnpRenderer RENDERER;
    static final FakeSonosHousehold HOUSEHOLD;
    static final FakeSonosPlayer LIVING_ROOM;
    static final FakeSonosPlayer KITCHEN_ROOM;
    static final Path DATA;

    static {
        try {
            RENDERER = new FakeUpnpRenderer();
            HOUSEHOLD = new FakeSonosHousehold();
            LIVING_ROOM = HOUSEHOLD.addPlayer("127.0.0.2", LIVING, "Living Room");
            KITCHEN_ROOM = HOUSEHOLD.addPlayer("127.0.0.3", KITCHEN, "Kitchen");
            // The responder answers from 127.0.0.1: the renderer's own address. The Sonos living room
            // announces itself from 127.0.0.2 with NOTIFY (see announceLivingRoom), as F1's rules require.
            SSDP = new FakeSsdpResponder();
            SSDP.answer(UpnpDiscovery.SEARCH_TARGET, RENDERER.searchResponse());
            DATA = Files.createTempDirectory("speakers-e2e");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shield.data-dir", DATA::toString);
        registry.add("home-control.ssdp.enabled", () -> "true");
        registry.add("home-control.ssdp.multicast-address", () -> "127.0.0.1");
        registry.add("home-control.ssdp.port", SSDP::port);
        registry.add("home-control.ssdp.listen-port", () -> "0");
        registry.add("home-control.ssdp.search-interval-seconds", () -> "1");
        registry.add("home-control.webos.enabled", () -> "false");
        registry.add("home-control.tizen.enabled", () -> "false");
        registry.add("home-control.upnp.poll-interval-seconds", () -> "1");
        registry.add("home-control.upnp.idle-poll-interval-seconds", () -> "1");
        registry.add("home-control.upnp.reconnect-initial-delay-seconds", () -> "30");
        registry.add("home-control.upnp.reconnect-max-delay-seconds", () -> "60");
        registry.add("home-control.sonos.poll-interval-seconds", () -> "1");
        registry.add("home-control.sonos.idle-poll-interval-seconds", () -> "1");
        registry.add("home-control.sonos.topology-interval-seconds", () -> "1");
    }

    @AfterAll
    static void stopFakes() {
        SSDP.close();
        RENDERER.close();
        HOUSEHOLD.close();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DeviceManager devices;

    @Autowired
    DeviceRegistry registry;

    @Autowired
    SsdpDiscovery ssdp;

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }

    private void awaitStatus(String id, DeviceStatus wanted) {
        await().atMost(WAIT).until(() -> devices.state(id).status() == wanted);
    }

    private void notify(String bindAddress, String message) throws IOException {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(bindAddress), 0))) {
            byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes, bytes.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), ssdp.listenPort())));
        }
    }

    private void announceLivingRoom() throws IOException {
        notify("127.0.0.2", "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: " + SonosDiscovery.SEARCH_TARGET
                + "\r\nNTS: ssdp:alive\r\nUSN: uuid:" + LIVING + "::" + SonosDiscovery.SEARCH_TARGET
                + "\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: " + LIVING_ROOM.location()
                + "\r\nX-RINCON-HOUSEHOLD: " + FakeSonosHousehold.HOUSEHOLD + "\r\n\r\n");
    }

    @Test
    void discoversControlsAndGroupsSpeakers() throws Exception {
        // 1. The renderer answers the search; the Sonos household is learned from one room's announcement.
        await().atMost(WAIT).untilAsserted(() -> {
            announceLivingRoom();
            assertThat(page("/setup")).contains("Kitchen Speaker").contains("Living Room").contains("Kitchen");
        });

        // 2. Added from the setup page.
        mockMvc.perform(post("/setup/add").param("adapter", "upnp").param("host", "127.0.0.1")
                        .param("port", String.valueOf(RENDERER.port())))
                .andExpect(status().is3xxRedirection());
        assertThat(registry.findById(RENDERER_ID)).hasValueSatisfying(device -> {
            assertThat(device.kind()).isEqualTo(DeviceKind.UPNP);
            assertThat(device.adapterSettings("upnp")).containsEntry("udn", FakeUpnpRenderer.UDN);
        });

        // 3. Connected with its volume; the drawer offers playback controls and the link form.
        await().atMost(WAIT).untilAsserted(() -> {
            DeviceState state = devices.state(RENDERER_ID);
            assertThat(state.status()).isEqualTo(DeviceStatus.CONNECTED);
            assertThat(state.volumeLevel()).isEqualTo(20);
        });
        assertThat(page("/?device=" + RENDERER_ID)).contains("/devices/" + RENDERER_ID + "/pause")
                .contains("/devices/" + RENDERER_ID + "/play");

        // 4. Volume.
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/volume").param("level", "40")).andExpect(status().isNoContent());
        assertThat(RENDERER.volume()).isEqualTo(40);

        // 5. A direct link streams to the renderer, with DIDL-Lite, and shows as now playing.
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/play").param("uri", "http://127.0.0.1:9/music/Bunny%20Song.flac"))
                .andExpect(status().isOk())
                .andExpect(content().string("Stream directly to this device (DLNA/UPnP)"));
        assertThat(RENDERER.currentUri()).isEqualTo("http://127.0.0.1:9/music/Bunny%20Song.flac");
        assertThat(RENDERER.currentMetadata()).contains("<dc:title>Bunny Song.flac</dc:title>").contains("audio/flac");
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(devices.state(RENDERER_ID).nowPlaying()).isNotNull();
            assertThat(devices.state(RENDERER_ID).nowPlaying().title()).isEqualTo("Bunny Song.flac");
            assertThat(devices.state(RENDERER_ID).nowPlaying().state()).isEqualTo(PlaybackState.PLAYING);
        });

        // 6. Pause, resume, stop.
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/pause")).andExpect(status().isNoContent());
        await().atMost(WAIT).until(() -> devices.state(RENDERER_ID).nowPlaying() != null
                && devices.state(RENDERER_ID).nowPlaying().state() == PlaybackState.PAUSED);
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/resume")).andExpect(status().isNoContent());
        await().atMost(WAIT).until(() -> devices.state(RENDERER_ID).nowPlaying() != null
                && devices.state(RENDERER_ID).nowPlaying().state() == PlaybackState.PLAYING);
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/stop")).andExpect(status().isNoContent());
        await().atMost(WAIT).until(() -> devices.state(RENDERER_ID).nowPlaying() == null);

        // 7. A format the renderer does not list is refused with a reason.
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/play").param("uri", "http://127.0.0.1:9/films/bunny.mp4"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("Kitchen Speaker cannot play video/mp4"));

        // 8. Gone: commands are 409. Back: its announcement skips the 30 s reconnect backoff.
        RENDERER.hangUp(true);
        awaitStatus(RENDERER_ID, DeviceStatus.DISCONNECTED);
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/volume").param("level", "10")).andExpect(status().isConflict());
        RENDERER.hangUp(false);
        notify("127.0.0.1", "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1"
                + "\r\nNTS: ssdp:alive\r\nUSN: uuid:5f9ec1b3-ed59-4f00-a3c1-2d2b4a1e0001::urn:schemas-upnp-org:device:MediaRenderer:1"
                + "\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: " + RENDERER.location() + "\r\n\r\n");
        awaitStatus(RENDERER_ID, DeviceStatus.CONNECTED);

        // 9. Both Sonos rooms added.
        for (FakeSonosPlayer room : new FakeSonosPlayer[]{LIVING_ROOM, KITCHEN_ROOM}) {
            mockMvc.perform(post("/setup/add").param("adapter", "sonos").param("host", room.host())
                            .param("port", String.valueOf(room.port())))
                    .andExpect(status().is3xxRedirection());
        }
        assertThat(registry.findById(LIVING_ID)).hasValueSatisfying(device -> assertThat(device.kind()).isEqualTo(DeviceKind.SONOS));
        assertThat(registry.findById(KITCHEN_ID)).hasValueSatisfying(device -> assertThat(device.kind()).isEqualTo(DeviceKind.SONOS));
        awaitStatus(LIVING_ID, DeviceStatus.CONNECTED);
        awaitStatus(KITCHEN_ID, DeviceStatus.CONNECTED);

        // 10. The kitchen drawer offers to join the living room.
        await().atMost(WAIT).untilAsserted(() -> assertThat(page("/?device=" + KITCHEN_ID))
                .contains("/devices/" + KITCHEN_ID + "/group/join/" + LIVING).contains("Join Living Room"));

        // 11. Joining.
        mockMvc.perform(post("/devices/" + KITCHEN_ID + "/group/join/" + LIVING))
                .andExpect(status().isNoContent())
                .andExpect(header().string("HX-Refresh", "true"));
        assertThat(HOUSEHOLD.coordinatorOf(KITCHEN)).isEqualTo(LIVING);
        await().atMost(WAIT).until(() -> devices.speakerTopology(KITCHEN_ID).map(SpeakerTopology::grouped).orElse(false));

        // 12. Playing on the grouped kitchen plays through the living room coordinator.
        LIVING_ROOM.clearCalls();
        KITCHEN_ROOM.clearCalls();
        mockMvc.perform(post("/devices/" + KITCHEN_ID + "/play").param("uri", "http://127.0.0.1:9/music/song.mp3"))
                .andExpect(status().isOk());
        assertThat(LIVING_ROOM.commandNames()).containsExactly("SetAVTransportURI", "Play");
        assertThat(KITCHEN_ROOM.commandNames()).isEmpty();
        await().atMost(WAIT).until(() -> devices.state(KITCHEN_ID).nowPlaying() != null
                && "song.mp3".equals(devices.state(KITCHEN_ID).nowPlaying().title()));

        // 13. Leaving.
        mockMvc.perform(post("/devices/" + KITCHEN_ID + "/group/leave")).andExpect(status().isNoContent());
        assertThat(KITCHEN_ROOM.commandNames()).last().isEqualTo("BecomeCoordinatorOfStandaloneGroup");
        assertThat(HOUSEHOLD.isCoordinator(KITCHEN)).isTrue();

        // 14. A renderer has no remote keys.
        mockMvc.perform(post("/devices/" + RENDERER_ID + "/key/HOME")).andExpect(status().isUnprocessableContent());

        // 15. Forgetting all three.
        for (String id : new String[]{RENDERER_ID, LIVING_ID, KITCHEN_ID}) {
            mockMvc.perform(post("/setup/forget").param("id", id)).andExpect(status().is3xxRedirection());
        }
        assertThat(registry.findAll()).isEmpty();
        assertThat(devices.states()).doesNotContainKeys(RENDERER_ID, LIVING_ID, KITCHEN_ID);
    }
}
