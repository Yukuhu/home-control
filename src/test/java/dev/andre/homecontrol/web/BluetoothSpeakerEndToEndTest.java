package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.FakeBluezClient;
import dev.andre.homecontrol.adapters.bluetooth.player.FakeMpvScript;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end over the real application: setup page, pairing, playback and control of a Bluetooth
 * speaker, against a fake BlueZ and a fake mpv (real subprocess, real Unix socket).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BluetoothSpeakerEndToEndTest.FakeBluez.class)
class BluetoothSpeakerEndToEndTest {

    static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
    static final String ID = "bluetooth-aa-bb-cc-dd-ee-ff";
    static final FakeBluezClient BLUEZ = new FakeBluezClient();
    static final Path ROOT;
    static final Path LOG;
    static final Path MPV;

    static {
        try {
            ROOT = Files.createTempDirectory("bluetooth-e2e");
            LOG = ROOT.resolve("mpv-log.jsonl");
            Files.createFile(ROOT.resolve("system_bus_socket")); // the host check only needs the path to exist
            MPV = FakeMpvScript.create(ROOT.resolve("bin"), Map.of(
                    "FAKE_MPV_LOG", LOG.toString(),
                    "FAKE_MPV_DURATION", "600",
                    "FAKE_MPV_FAIL_URLS_CONTAINING", "unreachable",
                    "FAKE_MPV_DEVICES", "pulse/alsa_output.platform-bcm2835_audio.stereo-fallback=Built-in Audio;"
                            + "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BLUEZ.addDevice(ADDRESS, "JBL Flip 5").icon("audio-card")
                .uuids(BluetoothDeviceInfo.A2DP_SINK, "0000110e-0000-1000-8000-00805f9b34fb").rssi(-58);
        BLUEZ.addDevice("11:22:33:44:55:66", "Pixel 9").icon("phone").uuids("0000110a-0000-1000-8000-00805f9b34fb");
    }

    @TestConfiguration
    static class FakeBluez {
        @Bean
        @Primary
        BluezClient fakeBluezClient() {
            return BLUEZ;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shield.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("home-control.ssdp.enabled", () -> "false");
        registry.add("home-control.bluetooth.enabled", () -> "true");
        registry.add("home-control.bluetooth.dbus-address", () -> "unix:path=" + ROOT.resolve("system_bus_socket"));
        registry.add("home-control.bluetooth.mpv-path", MPV::toString);
        registry.add("home-control.bluetooth.runtime-dir", () -> ROOT.resolve("run").toString());
        registry.add("home-control.bluetooth.scan-seconds", () -> "1");
        registry.add("home-control.bluetooth.poll-interval-seconds", () -> "1");
        registry.add("home-control.bluetooth.playing-poll-interval-seconds", () -> "1");
        registry.add("home-control.bluetooth.player-start-timeout-seconds", () -> "20");  // child JVM start
        registry.add("home-control.bluetooth.load-timeout-seconds", () -> "10");
        registry.add("home-control.bluetooth.command-timeout-seconds", () -> "3");
        registry.add("home-control.bluetooth.host-check-cache-seconds", () -> "1");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DeviceManager devices;

    @Autowired
    DeviceRegistry registry;

    private static final Duration WAIT = Duration.ofSeconds(20);

    private List<JsonNode> starts() throws IOException {
        return FakeMpvScript.log(LOG).stream().filter(line -> "start".equals(line.path("type").asString(""))).toList();
    }

    private List<List<String>> ipcCommands() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        for (JsonNode line : FakeMpvScript.log(LOG)) {
            if ("command".equals(line.path("type").asString(""))) {
                List<String> command = new ArrayList<>();
                line.path("command").forEach(a -> command.add(a.asString("")));
                commands.add(command);
            }
        }
        return commands;
    }

    private boolean anyFakeMpvAlive() throws IOException {
        for (JsonNode line : starts()) {
            long pid = line.path("pid").asLong(-1);
            if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    @AfterAll
    static void tearDown() {
        BLUEZ.close();
        await().atMost(WAIT).untilAsserted(() -> {
            for (JsonNode line : FakeMpvScript.log(LOG).stream().filter(l -> "start".equals(l.path("type").asString(""))).toList()) {
                long pid = line.path("pid").asLong(-1);
                assertThat(pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            }
        });
    }

    @Test
    void pairsPlaysControlsAndForgetsASpeaker() throws Exception {
        // 1.
        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bluetooth\"")))
                .andExpect(content().string(containsString("data-check=\"dbus-socket\"")))
                .andExpect(content().string(containsString("mpv v0.41.0-fake")))
                .andExpect(content().string(containsString("PipeWire or PulseAudio reachable (2 outputs)")))
                .andExpect(content().string(containsString("hci0 (00:1A:7D:DA:71:13)")));

        // 2.
        mockMvc.perform(post("/setup/bluetooth/scan")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/setup#bluetooth"));
        mockMvc.perform(get("/setup")).andExpect(status().isOk())
                .andExpect(content().string(containsString("JBL Flip 5")))
                .andExpect(content().string(containsString("name=\"address\" value=\"AA:BB:CC:DD:EE:FF\"")))
                .andExpect(content().string(containsString("1 other Bluetooth devices hidden")))
                .andExpect(content().string(not(containsString("Pixel 9"))));

        // 3.
        mockMvc.perform(post("/setup/bluetooth/pair").param("address", ADDRESS)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/?device=" + ID));
        assertThat(BLUEZ.calls()).contains("pair " + ADDRESS, "trust " + ADDRESS, "connect " + ADDRESS);
        var registered = registry.findById(ID).orElseThrow();
        assertThat(registered.kind().name()).isEqualTo("BLUETOOTH");
        assertThat(registered.host()).isEqualTo(ADDRESS);
        assertThat(registered.adapterSettings("bluetooth"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("address", ADDRESS, "adapter", "00:1A:7D:DA:71:13"));
        assertThat(devices.capabilities(ID).stream().map(Enum::name)).contains("LOCAL_AUDIO_SINK", "VOLUME");
        await().atMost(WAIT).untilAsserted(() -> assertThat(devices.state(ID).status().name()).isEqualTo("CONNECTED"));

        // 4.
        mockMvc.perform(get("/").param("device", ID)).andExpect(status().isOk())
                .andExpect(content().string(containsString("/devices/" + ID + "/pause")))
                .andExpect(content().string(containsString("/devices/" + ID + "/play")))
                .andExpect(content().string(containsString("This speaker plays through the server")));

        // 5.
        mockMvc.perform(post("/devices/" + ID + "/play").param("uri", "http://127.0.0.1:9/music/Bunny%20Song.mp3"))
                .andExpect(status().isOk())
                .andExpect(content().string("Play through the server on this Bluetooth speaker"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(starts()).hasSize(1));
        List<String> argsOfFirstStart = new ArrayList<>();
        starts().getFirst().path("args").forEach(a -> argsOfFirstStart.add(a.asString("")));
        assertThat(argsOfFirstStart).anyMatch(a -> a.equals("--audio-device=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1"))
                .noneMatch(a -> a.contains("127.0.0.1:9"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(ipcCommands())
                .contains(List.of("loadfile", "http://127.0.0.1:9/music/Bunny%20Song.mp3", "replace")));
        await().atMost(WAIT).untilAsserted(() -> {
            var nowPlaying = devices.state(ID).nowPlaying();
            assertThat(nowPlaying).isNotNull();
            assertThat(nowPlaying.title()).isEqualTo("Bunny Song.mp3");
            assertThat(nowPlaying.state().name()).isEqualTo("PLAYING");
            assertThat(nowPlaying.durationSeconds()).isEqualTo(600.0);
        });

        // 6.
        mockMvc.perform(post("/devices/" + ID + "/pause")).andExpect(status().isNoContent());
        await().atMost(WAIT).untilAsserted(() -> assertThat(devices.state(ID).nowPlaying().state().name()).isEqualTo("PAUSED"));
        mockMvc.perform(post("/devices/" + ID + "/resume")).andExpect(status().isNoContent());
        await().atMost(WAIT).untilAsserted(() -> assertThat(devices.state(ID).nowPlaying().state().name()).isEqualTo("PLAYING"));
        mockMvc.perform(post("/devices/" + ID + "/volume").param("level", "30")).andExpect(status().isNoContent());
        await().atMost(WAIT).untilAsserted(() -> assertThat(devices.state(ID).volumeLevel()).isEqualTo(30));
        assertThat(ipcCommands()).contains(List.of("set_property", "volume", "30"));
        mockMvc.perform(post("/devices/" + ID + "/mute").param("muted", "true")).andExpect(status().isNoContent());
        await().atMost(WAIT).untilAsserted(() -> assertThat(devices.state(ID).muted()).isTrue());

        // 7.
        mockMvc.perform(post("/devices/" + ID + "/play").param("uri", "http://127.0.0.1:9/films/bunny.mp4"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("a Bluetooth speaker plays audio streams only")));

        // 8.
        mockMvc.perform(post("/devices/" + ID + "/play").param("uri", "http://127.0.0.1:9/music/unreachable.mp3"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("JBL Flip 5 could not play the stream: the stream could not be loaded (loading failed)"));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(devices.state(ID).nowPlaying()).isNull();
            assertThat(anyFakeMpvAlive()).isFalse();
        });

        // 9.
        mockMvc.perform(post("/devices/" + ID + "/play").param("uri", "http://127.0.0.1:9/music/Bunny%20Song.mp3"))
                .andExpect(status().isOk());
        BLUEZ.device(ADDRESS).connected(false);
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(devices.state(ID).status().name()).isEqualTo("DISCONNECTED");
            assertThat(devices.state(ID).nowPlaying()).isNull();
            assertThat(anyFakeMpvAlive()).isFalse();
        });

        // 10.
        mockMvc.perform(post("/devices/" + ID + "/play").param("uri", "http://127.0.0.1:9/music/Bunny%20Song.mp3"))
                .andExpect(status().isOk());
        assertThat(BLUEZ.calls()).filteredOn(call -> call.equals("connect " + ADDRESS)).hasSizeGreaterThanOrEqualTo(2);
        mockMvc.perform(post("/devices/" + ID + "/stop")).andExpect(status().isNoContent());
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(anyFakeMpvAlive()).isFalse();
            assertThat(devices.state(ID).nowPlaying()).isNull();
        });
        mockMvc.perform(post("/devices/" + ID + "/pause")).andExpect(status().isBadGateway())
                .andExpect(content().string("Nothing is playing on JBL Flip 5"));

        // 11.
        mockMvc.perform(post("/setup/bluetooth/audio-device").param("id", ID)
                        .param("audioDevice", "pulse/alsa_output.platform-bcm2835_audio.stereo-fallback"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/devices/" + ID + "/play").param("uri", "http://127.0.0.1:9/music/Bunny%20Song.mp3"))
                .andExpect(status().isOk());
        await().atMost(WAIT).untilAsserted(() -> {
            List<String> newestArgs = new ArrayList<>();
            starts().getLast().path("args").forEach(a -> newestArgs.add(a.asString("")));
            assertThat(newestArgs).anyMatch(a -> a.equals("--audio-device=pulse/alsa_output.platform-bcm2835_audio.stereo-fallback"));
        });
        mockMvc.perform(post("/devices/" + ID + "/stop")).andExpect(status().isNoContent());

        // 12.
        mockMvc.perform(post("/setup/bluetooth/disconnect").param("id", ID)).andExpect(status().is3xxRedirection());
        await().atMost(WAIT).untilAsserted(() -> assertThat(BLUEZ.calls()).endsWith("disconnect " + ADDRESS));
        mockMvc.perform(post("/setup/bluetooth/connect").param("id", "ghost")).andExpect(status().isNotFound());

        // 13.
        mockMvc.perform(post("/setup/forget").param("id", ID)).andExpect(status().is3xxRedirection());
        assertThat(registry.findById(ID)).isEmpty();
        await().atMost(WAIT).untilAsserted(() -> assertThat(BLUEZ.calls()).endsWith("remove " + ADDRESS));
        assertThat(anyFakeMpvAlive()).isFalse();
        String logText = Files.readString(LOG);
        assertThat(logText).doesNotContain("secret");
        for (JsonNode line : starts()) {
            assertThat(line.toString()).doesNotContain("http");
        }
    }
}
