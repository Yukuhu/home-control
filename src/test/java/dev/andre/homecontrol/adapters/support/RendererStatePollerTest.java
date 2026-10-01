package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.adapters.upnp.protocol.PlayedItem;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.AV_TRANSPORT;
import static dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer.RENDERING_CONTROL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RendererStatePollerTest {

    private static final String STREAM = "http://192.168.1.20:8096/Audio/c0ffee/stream.flac";

    private final List<DeviceState> seen = new CopyOnWriteArrayList<>();
    private FakeUpnpRenderer fake;
    private StatePublisher publisher;
    private RendererStatePoller poller;
    private ServiceEndpoint av;
    private ServiceEndpoint rc;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeUpnpRenderer();
        String base = "http://127.0.0.1:" + fake.port();
        av = new ServiceEndpoint(AV_TRANSPORT, URI.create(base + "/upnp/control/AVTransport1"), null);
        rc = new ServiceEndpoint(RENDERING_CONTROL, URI.create(base + "/upnp/control/RenderingControl1"), null);
        publisher = new StatePublisher("kitchen", DeviceState.initial(), seen::add);
        RendererCommands commands = new RendererCommands(
                new SoapClient(SoapClient.httpClient(Duration.ofSeconds(1)), Duration.ofSeconds(1)), "Kitchen Speaker");
        poller = new RendererStatePoller("kitchen", commands, publisher, Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void publishesConnectedWithTheVolumeInPercent() throws Exception {
        fake.setVolume(20);

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        DeviceState state = publisher.current();
        assertThat(state.status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(state.powerOn()).isTrue();
        assertThat(state.volumeLevel()).isEqualTo(20);
        assertThat(state.volumeMax()).isEqualTo(100);
        assertThat(state.nowPlaying()).isNull();
        assertThat(seen).hasSize(1);
    }

    @Test
    void readsWhatPlaysFromTheRenderersMetadata() throws Exception {
        fake.playElsewhere(STREAM, Files.readString(Path.of("src/test/resources/fixtures/upnp/position-metadata.xml")));

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(publisher.current().nowPlaying()).isNotNull()
                .extracting(NowPlaying::title, NowPlaying::state).containsExactly("Carrot Waltz", PlaybackState.PLAYING);
    }

    @Test
    void usesTheTitleItPlayedWhenTheRendererForgetsMetadata() throws Exception {
        fake.playElsewhere(STREAM, "");
        poller.played(new PlayedItem(STREAM, "Bunny Song"));

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(publisher.current().nowPlaying()).extracting(NowPlaying::title).isEqualTo("Bunny Song");
    }

    @Test
    void anOptionalVolumeThatFaultsIsLeftOut() throws Exception {
        fake.fail("GetVolume", 501, "Action Failed", 1);

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(publisher.current().volumeMax()).isZero();
    }

    @Test
    void aRequiredVolumeThatFaultsFailsTheRead() {
        fake.fail("GetVolume", 501, "Action Failed", 1);

        assertThatThrownBy(() -> poller.read(new RendererStatePoller.Endpoints(av, rc, 100, true)))
                .isInstanceOf(SoapFault.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void withoutRenderingControlThereIsNoVolume() throws Exception {
        poller.read(new RendererStatePoller.Endpoints(av, null, 0, false));

        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(publisher.current().volumeMax()).isZero();
    }

    @Test
    void pollsFasterWhileSomethingPlays() throws Exception {
        assertThat(poller.nextPollDelay()).isEqualTo(Duration.ofSeconds(5));
        fake.playElsewhere(STREAM, "");

        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        assertThat(poller.nextPollDelay()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void lostPublishesDisconnectedAndForgetsWhatPlays() throws Exception {
        fake.playElsewhere(STREAM, "");
        poller.read(new RendererStatePoller.Endpoints(av, rc, 100, false));

        poller.lost();

        assertThat(publisher.current().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(publisher.current().nowPlaying()).isNull();
        assertThat(poller.nextPollDelay()).isEqualTo(Duration.ofSeconds(5));
    }
}
