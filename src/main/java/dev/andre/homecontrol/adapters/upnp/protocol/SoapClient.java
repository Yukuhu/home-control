package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.discovery.ssdp.DeviceFetch;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** UPnP control: one SOAP POST per action, HTTP/1.1, answers capped (UDA 1.1 §3). */
public final class SoapClient {

    public static final String USER_AGENT = DeviceFetch.USER_AGENT;
    /** SOAP answers are small; a device that sends more is refused rather than read into memory. */
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final String ENVELOPE_START = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>";
    private static final String ENVELOPE_END = "</s:Body></s:Envelope>";

    private static final Pattern SERVICE_TYPE = Pattern.compile("^urn:[A-Za-z0-9.:\\-]+$");
    private static final Pattern ACTION = Pattern.compile("^[A-Za-z]\\w*$");

    private final HttpClient http;
    private final Duration timeout;

    public SoapClient(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    /** The only way device HTTP clients are built: HTTP/1.1 (no h2c upgrade), no redirects. */
    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
    }

    /** Service types go into an XML attribute and the SOAPACTION header unescaped: only plain URNs pass. */
    public static boolean isValidServiceType(String type) {
        return type != null && SERVICE_TYPE.matcher(type).matches();
    }

    public static String envelope(SoapRequest request) {
        StringBuilder xml = new StringBuilder(ENVELOPE_START)
                .append("<u:").append(request.action()).append(" xmlns:u=\"").append(request.serviceType()).append("\">");
        request.arguments().forEach((name, value) -> xml.append('<').append(name).append('>')
                .append(UpnpXml.escape(value)).append("</").append(name).append('>'));
        return xml.append("</u:").append(request.action()).append('>').append(ENVELOPE_END).toString();
    }

    public Map<String, String> call(URI controlUrl, SoapRequest request) throws IOException, SoapFault {
        if (!isValidServiceType(request.serviceType()) || !ACTION.matcher(request.action()).matches()
                || !request.arguments().keySet().stream().allMatch(name -> ACTION.matcher(name).matches())) {
            throw new SoapFault(0, "Refusing a malformed service type or action");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(controlUrl)
                .timeout(timeout)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPACTION", "\"" + request.serviceType() + "#" + request.action() + "\"")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(envelope(request), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = DeviceFetch.send(http, httpRequest, MAX_RESPONSE_BYTES, timeout);
        } catch (HttpConnectTimeoutException e) {
            throw e; // unreachable, not slow
        } catch (HttpTimeoutException _) {
            throw new SoapTimeoutException("No answer to " + request.action() + " within " + timeout.toMillis() + " ms");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while calling " + request.action());
        }
        byte[] body = response.body();
        return switch (response.statusCode()) {
            case 200 -> arguments(body, request.action());
            case 500 -> throw fault(body, request.action());
            default -> throw new IOException("HTTP " + response.statusCode() + " from " + controlUrl.getHost()
                    + " for " + request.action());
        };
    }

    static Map<String, String> arguments(byte[] body, String action) throws SoapFault {
        try {
            Element response = UpnpXml.firstDescendant(UpnpXml.parse(body), action + "Response")
                    .orElseThrow(() -> new IllegalArgumentException("no " + action + "Response"));
            Map<String, String> values = new LinkedHashMap<>();
            UpnpXml.childElements(response).forEach(child -> values.put(UpnpXml.localName(child), child.getTextContent()));
            return Collections.unmodifiableMap(values);
        } catch (IllegalArgumentException _) {
            throw new SoapFault(0, "Unreadable answer to " + action);
        }
    }

    static SoapFault fault(byte[] body, String action) {
        try {
            Element root = UpnpXml.parse(body);
            Optional<Element> error = UpnpXml.firstDescendant(root, "UPnPError");
            int code = error.flatMap(e -> UpnpXml.childText(e, "errorCode")).map(Integer::parseInt).orElse(0);
            String description = error.flatMap(e -> UpnpXml.childText(e, "errorDescription"))
                    .or(() -> UpnpXml.firstDescendant(root, "faultstring").map(e -> e.getTextContent().trim()))
                    .orElse("");
            return code == 0 && description.isEmpty() ? new SoapFault(0, "HTTP 500 for " + action) : new SoapFault(code, description);
        } catch (IllegalArgumentException _) { // includes NumberFormatException
            return new SoapFault(0, "HTTP 500 for " + action);
        }
    }
}
