package dev.andre.homecontrol.adapters.upnp.protocol;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoapClientTest {

    static final String URL = "http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac"
            + "?static=true&mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&ApiKey=t";
    private static final String AV = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String FRAME_START = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope"
            + " xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>";
    private static final String FRAME_END = "</s:Body></s:Envelope>";
    private static final String FAULT = FRAME_START + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
            + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>714</errorCode>"
            + "<errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault>" + FRAME_END;

    private HttpServer server;
    private volatile int status = 200;
    private volatile String answer = "";
    private volatile long delayMillis;
    private volatile String method;
    private volatile String protocol;
    private volatile Map<String, String> headers;
    private volatile String body;
    private final SoapClient client = new SoapClient(SoapClient.httpClient(Duration.ofSeconds(1)), Duration.ofMillis(500));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/control", exchange -> {
            try (exchange) {
                method = exchange.getRequestMethod();
                protocol = exchange.getProtocol();
                headers = Map.of(
                        "SOAPACTION", String.valueOf(exchange.getRequestHeaders().getFirst("SOAPACTION")),
                        "Content-Type", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                        "Upgrade", String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade")));
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // the client gave up
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI control() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/control");
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/upnp/" + name)).strip();
    }

    @Test
    void buildsTheNormativeEnvelope() throws IOException {
        assertThat(SoapClient.envelope(UpnpActions.setAvTransportUri(AV, URL, fixture("didl-track.xml"))))
                .isEqualTo(fixture("set-av-transport-uri-envelope.xml"));
        assertThat(SoapClient.envelope(UpnpActions.play(AV))).isEqualTo(FRAME_START
                + "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>"
                + FRAME_END);
        assertThat(SoapClient.envelope(UpnpActions.setVolume(RC, 40))).isEqualTo(FRAME_START
                + "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"><InstanceID>0</InstanceID>"
                + "<Channel>Master</Channel><DesiredVolume>40</DesiredVolume></u:SetVolume>" + FRAME_END);
        assertThat(SoapClient.envelope(UpnpActions.setMute(RC, true))).contains("<DesiredMute>1</DesiredMute>");
        assertThat(SoapClient.envelope(UpnpActions.getProtocolInfo("urn:schemas-upnp-org:service:ConnectionManager:1")))
                .contains("<u:GetProtocolInfo xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\"></u:GetProtocolInfo>");
    }

    @Test
    void postsOverHttp11WithTheSoapActionHeader() throws Exception {
        answer = FRAME_START + "<u:GetVolumeResponse xmlns:u=\"" + RC + "\"><CurrentVolume>35</CurrentVolume></u:GetVolumeResponse>" + FRAME_END;

        assertThat(client.call(control(), UpnpActions.getVolume(RC))).isEqualTo(Map.of("CurrentVolume", "35"));

        assertThat(method).isEqualTo("POST");
        assertThat(protocol).isEqualTo("HTTP/1.1");
        assertThat(headers).containsEntry("SOAPACTION", "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"")
                .containsEntry("Content-Type", "text/xml; charset=\"utf-8\"")
                .containsEntry("Upgrade", "null");
        assertThat(body).isEqualTo(SoapClient.envelope(UpnpActions.getVolume(RC)));
    }

    @Test
    void aUpnpErrorIsASoapFault() {
        status = 500;
        answer = FAULT;

        assertThatThrownBy(() -> client.call(control(), UpnpActions.play(AV)))
                .isInstanceOfSatisfying(SoapFault.class, fault -> {
                    assertThat(fault.errorCode()).isEqualTo(714);
                    assertThat(fault.description()).isEqualTo("Illegal MIME-type");
                    assertThat(fault.getMessage()).isEqualTo("UPnP error 714: Illegal MIME-type");
                });

        answer = FAULT.replace("<errorCode>714</errorCode>", "<errorCode>701</errorCode>")
                .replace("<errorDescription>Illegal MIME-type</errorDescription>", "");
        assertThatThrownBy(() -> client.call(control(), UpnpActions.play(AV)))
                .isInstanceOfSatisfying(SoapFault.class, fault -> {
                    assertThat(fault.errorCode()).isEqualTo(701);
                    assertThat(fault.description()).isEqualTo("Transition not available");
                });
    }

    @Test
    void aSilentDeviceIsATimeout() {
        delayMillis = 2000;

        assertThatThrownBy(() -> client.call(control(), UpnpActions.play(AV))).isInstanceOf(SoapTimeoutException.class);
    }

    @Test
    void otherStatusesAreConnectionProblems() throws IOException {
        status = 404;
        assertThatThrownBy(() -> client.call(control(), UpnpActions.play(AV)))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(SoapTimeoutException.class);

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        assertThatThrownBy(() -> client.call(URI.create("http://127.0.0.1:" + closedPort + "/control"), UpnpActions.play(AV)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void anUnreadableAnswerIsAFault() {
        answer = "garbage";
        assertThatThrownBy(() -> client.call(control(), UpnpActions.getVolume(RC)))
                .isInstanceOfSatisfying(SoapFault.class, fault -> assertThat(fault.errorCode()).isZero());

        answer = FRAME_START + "<u:SomethingElse xmlns:u=\"urn:x\"/>" + FRAME_END;
        assertThatThrownBy(() -> client.call(control(), UpnpActions.getVolume(RC)))
                .isInstanceOfSatisfying(SoapFault.class, fault -> assertThat(fault.errorCode()).isZero());
    }

    @Test
    void anOversizedAnswerIsRefused() {
        answer = FRAME_START + "<u:GetVolumeResponse xmlns:u=\"" + RC + "\"><CurrentVolume>35</CurrentVolume><Pad>"
                + "a".repeat(SoapClient.MAX_RESPONSE_BYTES) + "</Pad></u:GetVolumeResponse>" + FRAME_END;

        assertThatThrownBy(() -> client.call(control(), UpnpActions.getVolume(RC)))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(SoapTimeoutException.class)
                .hasMessageContaining("cap");
    }

    @Test
    void requestsNeverPrintTheirArguments() {
        assertThat(UpnpActions.setAvTransportUri(AV, "http://h/x?ApiKey=secret-key", "").toString())
                .doesNotContain("secret-key")
                .contains("SetAVTransportURI");
    }
}
