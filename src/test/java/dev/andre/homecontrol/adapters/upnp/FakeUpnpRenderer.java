package dev.andre.homecontrol.adapters.upnp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpXml;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An in-process UPnP media renderer: JDK HttpServer serving a device description, the
 * RenderingControl SCPD and SOAP control endpoints, with just enough state to observe a session.
 */
public class FakeUpnpRenderer implements AutoCloseable {

    public static final String UDN = "uuid:5f9ec1b3-ed59-4f00-a3c1-2d2b4a1e0001";
    public static final String FRIENDLY_NAME = "Kitchen Speaker";
    public static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String AUDIO_SINK =
            "http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/mp4:*,http-get:*:audio/ogg:*";

    /** Where things live on the device. Task 2 adds a Sonos layout. */
    public record Layout(String descriptionPath, String avTransportPath, String renderingControlPath,
                         String connectionManagerPath, String descriptionFixture) {
        public static final Layout GENERIC = new Layout("/description.xml", "/upnp/control/AVTransport1",
                "/upnp/control/RenderingControl1", "/upnp/control/ConnectionManager1", "fixtures/upnp/renderer-description.xml");
    }

    public record Call(String path, String soapAction, String action, Map<String, String> arguments) {
        public String argument(String name) {
            return arguments.getOrDefault(name, "");
        }
    }

    /** Thrown from {@link #perform} to answer with a UPnP fault. */
    protected static final class Fault extends RuntimeException {
        final int code;
        final String description;

        public Fault(int code, String description) {
            super(description, null, false, false);
            this.code = code;
            this.description = description;
        }
    }

    private record PlannedFault(int code, String description, int remaining) {
    }

    private final Layout layout;
    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private final Map<String, PlannedFault> faults = new ConcurrentHashMap<>();

    protected volatile String transportState = "NO_MEDIA_PRESENT";
    protected volatile String currentUri = "";
    protected volatile String currentMetadata = "";
    private volatile int volume = 20;
    private volatile int volumeMax = 100;
    private volatile boolean muted;
    private volatile String sink = AUDIO_SINK;
    private volatile String relTime = "0:00:00";
    private volatile String trackDuration = "0:00:00";
    private volatile boolean echoMetadata = true;
    private volatile boolean hangUp;

    public FakeUpnpRenderer() throws IOException {
        this("127.0.0.1", Layout.GENERIC);
    }

    protected FakeUpnpRenderer(String bindAddress, Layout layout) throws IOException {
        this.layout = layout;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String host() {
        return server.getAddress().getAddress().getHostAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI location() {
        return URI.create("http://" + host() + ":" + port() + layout.descriptionPath());
    }

    public String searchResponse() throws IOException {
        return FakeSsdpResponder.fixture("renderer-search-response.txt", host(), port());
    }

    /** A registered device for this renderer, as B's addDiscovered would store it. */
    public Device device(String id) {
        return new Device(id, FRIENDLY_NAME, DeviceKind.UPNP, host(),
                Map.of("upnp", Map.of("udn", UDN, "location", location().toString(), "model", "Acme Audio StreamBox 2")),
                Instant.now());
    }

    // --- test hooks -------------------------------------------------------------------------

    public void fail(String action, int code, String description, int times) {
        faults.put(action, new PlannedFault(code, description, times));
    }

    public void hangUp(boolean hangUp) {
        this.hangUp = hangUp;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    public void setVolumeMax(int volumeMax) {
        this.volumeMax = volumeMax;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public void setPosition(String relTime, String trackDuration) {
        this.relTime = relTime;
        this.trackDuration = trackDuration;
    }

    /** false: GetPositionInfo answers TrackMetaData NOT_IMPLEMENTED, as many cheap renderers do. */
    public void echoMetadata(boolean echo) {
        this.echoMetadata = echo;
    }

    /** Something another controller started. */
    public void playElsewhere(String uri, String metadata) {
        currentUri = uri;
        currentMetadata = metadata;
        transportState = "PLAYING";
    }

    public String transportState() {
        return transportState;
    }

    public String currentUri() {
        return currentUri;
    }

    public String currentMetadata() {
        return currentMetadata;
    }

    public int volume() {
        return volume;
    }

    public boolean muted() {
        return muted;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> calls(String action) {
        return calls.stream().filter(call -> call.action().equals(action)).toList();
    }

    /** Calls that change something (everything but Get*), in order. */
    public List<String> commandNames() {
        return calls.stream().map(Call::action).filter(action -> !action.startsWith("Get")).toList();
    }

    /** Paths of documents (description, SCPD) fetched with GET, in order. */
    public List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    public void clearCalls() {
        calls.clear();
    }

    // --- HTTP ----------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        if (hangUp) {
            exchange.close(); // no status line: the client sees an I/O error
            return;
        }
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                requestedPaths.add(path);
                String document = document(path);
                reply(exchange, document == null ? 404 : 200, document == null ? "" : document);
                return;
            }
            String serviceType = serviceType(path);
            if (!"POST".equals(exchange.getRequestMethod()) || serviceType == null) {
                reply(exchange, 404, "");
                return;
            }
            soap(exchange, path, serviceType);
        }
    }

    protected String document(String path) throws IOException {
        if (path.equals(layout.descriptionPath())) {
            return resource(layout.descriptionFixture());
        }
        if (path.equals("/scpd/RenderingControl1.xml")) {
            return resource("fixtures/upnp/rendering-control-scpd.xml").replace("{volumeMax}", String.valueOf(volumeMax));
        }
        return null;
    }

    protected String serviceType(String path) {
        if (path.equals(layout.avTransportPath())) {
            return AV_TRANSPORT;
        }
        if (path.equals(layout.renderingControlPath())) {
            return RENDERING_CONTROL;
        }
        if (path.equals(layout.connectionManagerPath())) {
            return CONNECTION_MANAGER;
        }
        return null;
    }

    private void soap(HttpExchange exchange, String path, String serviceType) throws IOException {
        Element body = UpnpXml.firstDescendant(UpnpXml.parse(exchange.getRequestBody().readAllBytes()), "Body").orElseThrow();
        Element request = UpnpXml.childElements(body).getFirst();
        String action = UpnpXml.localName(request);
        Map<String, String> arguments = new LinkedHashMap<>();
        UpnpXml.childElements(request).forEach(argument -> arguments.put(UpnpXml.localName(argument), argument.getTextContent()));
        calls.add(new Call(path, exchange.getRequestHeaders().getFirst("SOAPACTION"), action,
                Collections.unmodifiableMap(arguments)));
        try {
            PlannedFault planned = faults.get(action);
            if (planned != null) {
                if (planned.remaining() <= 1) {
                    faults.remove(action);
                } else {
                    faults.put(action, new PlannedFault(planned.code(), planned.description(), planned.remaining() - 1));
                }
                throw new Fault(planned.code(), planned.description());
            }
            reply(exchange, 200, responseEnvelope(serviceType, action, perform(serviceType, action, arguments)));
        } catch (Fault fault) {
            reply(exchange, 500, faultEnvelope(fault.code, fault.description));
        }
    }

    /** Device behaviour. Subclasses (Sonos) intercept actions and delegate the rest here. */
    protected Map<String, String> perform(String serviceType, String action, Map<String, String> arguments) {
        return switch (action) {
            case "SetAVTransportURI" -> {
                currentUri = arguments.getOrDefault("CurrentURI", "");
                currentMetadata = arguments.getOrDefault("CurrentURIMetaData", "");
                transportState = "STOPPED";
                yield Map.of();
            }
            case "Play" -> {
                if (currentUri.isEmpty()) {
                    throw new Fault(701, "Transition not available");
                }
                transportState = "PLAYING";
                yield Map.of();
            }
            case "Pause" -> {
                transportState = "PAUSED_PLAYBACK";
                yield Map.of();
            }
            case "Stop" -> {
                transportState = currentUri.isEmpty() ? "NO_MEDIA_PRESENT" : "STOPPED";
                yield Map.of();
            }
            case "GetTransportInfo" -> ordered("CurrentTransportState", transportState,
                    "CurrentTransportStatus", "OK", "CurrentSpeed", "1");
            case "GetPositionInfo" -> ordered("Track", currentUri.isEmpty() ? "0" : "1",
                    "TrackDuration", trackDuration,
                    "TrackMetaData", echoMetadata ? currentMetadata : "NOT_IMPLEMENTED",
                    "TrackURI", currentUri, "RelTime", relTime, "AbsTime", "NOT_IMPLEMENTED",
                    "RelCount", "2147483647", "AbsCount", "2147483647");
            case "GetVolume" -> ordered("CurrentVolume", String.valueOf(volume));
            case "SetVolume" -> {
                volume = Integer.parseInt(arguments.getOrDefault("DesiredVolume", "0"));
                yield Map.of();
            }
            case "GetMute" -> ordered("CurrentMute", muted ? "1" : "0");
            case "SetMute" -> {
                String desired = arguments.getOrDefault("DesiredMute", "0");
                muted = desired.equals("1") || desired.equalsIgnoreCase("true");
                yield Map.of();
            }
            case "GetProtocolInfo" -> ordered("Source", "", "Sink", sink);
            default -> throw new Fault(401, "Invalid Action");
        };
    }

    protected static Map<String, String> ordered(String... namesAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            map.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return map;
    }

    static String responseEnvelope(String serviceType, String action, Map<String, String> result) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
                .append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">");
        result.forEach((name, value) -> xml.append('<').append(name).append('>').append(UpnpXml.escape(value))
                .append("</").append(name).append('>'));
        return xml.append("</u:").append(action).append("Response></s:Body></s:Envelope>").toString();
    }

    static String faultEnvelope(int code, String description) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>"
                + "<faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>" + code + "</errorCode><errorDescription>" + UpnpXml.escape(description) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=\"utf-8\"");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    protected static String resource(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/" + name));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
