package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.AUDIO_SINK;
import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.AV_TRANSPORT;
import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.CONNECTION_MANAGER;
import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.RENDERING_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RendererCommandsTest {

    private FakeUpnpRenderer fake;
    private ServiceEndpoint av;
    private ServiceEndpoint rc;
    private ServiceEndpoint cm;
    private final RendererCommands commands = new RendererCommands(
            new SoapClient(SoapClient.httpClient(Duration.ofSeconds(1)), Duration.ofSeconds(1)), "Kitchen Speaker");
    private final Action.PlayMedia song = new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/song.flac"),
            "audio/flac", "Bunny Song", "The Rabbits");

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeUpnpRenderer();
        String base = "http://127.0.0.1:" + fake.port();
        av = new ServiceEndpoint(AV_TRANSPORT, URI.create(base + "/upnp/control/AVTransport1"), null);
        rc = new ServiceEndpoint(RENDERING_CONTROL, URI.create(base + "/upnp/control/RenderingControl1"), null);
        cm = new ServiceEndpoint(CONNECTION_MANAGER, URI.create(base + "/upnp/control/ConnectionManager1"), null);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void playsAUrlTheRendererAccepts() {
        commands.playUri(av, ProtocolInfo.parseSink(AUDIO_SINK), song, DidlLite.DLNA_STREAMING);

        assertThat(fake.commandNames()).containsExactly("SetAVTransportURI", "Play");
        assertThat(fake.currentUri()).isEqualTo("http://127.0.0.1:9/music/song.flac");
        assertThat(fake.currentMetadata()).contains("protocolInfo=\"http-get:*:audio/flac:DLNA.ORG_OP=01")
                .contains("<dc:title>Bunny Song</dc:title>");
        assertThat(fake.transportState()).isEqualTo("PLAYING");
        assertThat(fake.calls().getFirst().soapAction()).isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"");
    }

    @Test
    void retriesOnceAfterStoppingWhenTheTransportIsLocked() {
        fake.fail("SetAVTransportURI", 705, "Transport is locked", 1);

        commands.playUri(av, ProtocolInfo.parseSink(AUDIO_SINK), song, DidlLite.DLNA_STREAMING);

        assertThat(fake.commandNames()).containsExactly("SetAVTransportURI", "Stop", "SetAVTransportURI", "Play");
    }

    @Test
    void refusesAFormatTheRendererCannotPlay() {
        Action.PlayMedia film = new Action.PlayMedia(URI.create("http://h/film.mp4"), "video/mp4", "Film", null);

        assertThatThrownBy(() -> commands.playUri(av, ProtocolInfo.parseSink(AUDIO_SINK), film, "*"))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("Kitchen Speaker cannot play video/mp4");
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void mapsFaultsTimeoutsAndLostConnections() {
        fake.fail("Play", 701, "Transition not available", 1);
        assertThatThrownBy(() -> commands.transport(av, UpnpActions.play(AV_TRANSPORT), "resume playback"))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Kitchen Speaker refused to resume playback (UPnP error 701: Transition not available)");

        fake.hangUp(true);
        assertThatThrownBy(() -> commands.transport(av, UpnpActions.play(AV_TRANSPORT), "resume playback"))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Kitchen Speaker could not be reached to resume playback");
    }

    @Test
    void aMalformedServiceTypeIsAFailedCommand() {
        ServiceEndpoint bad = new ServiceEndpoint("urn:x\"/>", av.controlUrl(), null);

        assertThatThrownBy(() -> commands.transport(bad, UpnpActions.play(bad.serviceType()), "resume playback"))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("Kitchen Speaker refused to resume playback");
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void readsTransportVolumeAndSink() throws Exception {
        TransportInfo info = commands.transportInfo(av);
        assertThat(info.state()).isEqualTo("NO_MEDIA_PRESENT");
        assertThat(info.active()).isFalse();

        fake.setVolume(30);
        assertThat(commands.volume(rc, 60)).isEqualTo(new VolumeReading(50, false));
        assertThat(commands.sink(cm).match("audio/flac")).contains("audio/flac");
    }

    @Test
    void setsVolumeInDeviceUnits() {
        commands.setVolume(rc, 50, 60);
        assertThat(fake.volume()).isEqualTo(30);

        commands.setMute(rc, true);
        assertThat(fake.muted()).isTrue();
        assertThat(fake.calls("SetMute").getFirst().argument("DesiredMute")).isEqualTo("1");
    }
}
