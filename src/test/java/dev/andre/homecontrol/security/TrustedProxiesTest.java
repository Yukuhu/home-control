package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behind a reverse proxy, only the proxies the owner names may say whom they forward for. */
@ExtendWith(OutputCaptureExtension.class)
class TrustedProxiesTest {

    private WebServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** A real Tomcat with the customizer applied, answering every request with what it believes about the client. */
    private int serve(TrustedProxies proxies) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        proxies.customize(factory);
        server = factory.getWebServer(context -> context.addServlet("echo", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getWriter().write(request.getRemoteAddr() + " " + request.getScheme()
                        + " " + request.isSecure()
                        + (request.getParameter("where") == null ? ""
                        : " " + request.getServerName() + ":" + request.getServerPort()));
            }
        }).addMapping("/"));
        server.start();
        return server.getPort();
    }

    private static String seen(int port, Map<String, String> headers) throws Exception {
        return seen(port, "/", headers);
    }

    private static String seen(int port, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        headers.forEach(request::header);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private static final Map<String, String> FORWARDED = Map.of(
            "X-Forwarded-For", "198.51.100.7, 203.0.113.9", "X-Forwarded-Proto", "https");

    @Test
    void aTrustedProxyNamesTheBrowserItForwardsForAndItsScheme() throws Exception {
        int port = serve(new TrustedProxies(List.of("127.0.0.1"), new MockEnvironment()));

        assertThat(seen(port, FORWARDED)).as("the rightmost address the proxy added, not one the browser sent")
                .isEqualTo("203.0.113.9 https true");
        assertThat(seen(port, Map.of())).isEqualTo("127.0.0.1 http false");
    }

    /** What the YouTube sign-in shows as its callback: the address the browser used, not the app's own. */
    @Test
    void aTrustedProxyNamesTheHostAndPortTheBrowserUsed() throws Exception {
        int port = serve(new TrustedProxies(List.of("127.0.0.1"), new MockEnvironment()));

        assertThat(seen(port, "/?where", Map.of("X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "home.example.org", "X-Forwarded-Port", "8443")))
                .isEqualTo("203.0.113.9 https true home.example.org:8443");
        assertThat(seen(port, "/?where", Map.of("X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https")))
                .as("without them, the proxy's scheme's port and the Host the proxy sent")
                .isEqualTo("203.0.113.9 https true 127.0.0.1:443");
    }

    @Test
    void withoutTrustedProxiesNobodyMaySayWhomItForwardsFor() throws Exception {
        int port = serve(new TrustedProxies(List.of(), new MockEnvironment()));

        assertThat(seen(port, FORWARDED)).isEqualTo("127.0.0.1 http false");
    }

    @Test
    void anotherAddressThanTheTrustedProxyIsTakenAtItsWord() throws Exception {
        int port = serve(new TrustedProxies(List.of("192.0.2.1"), new MockEnvironment()));

        assertThat(seen(port, FORWARDED)).isEqualTo("127.0.0.1 http false");
    }

    @Test
    void addressesMatchExactlyInTheFormTomcatReportsThem() {
        Pattern trusted = Pattern.compile(TrustedProxies.pattern(List.of("::1", "192.168.1.5")));

        assertThat(List.of("0:0:0:0:0:0:0:1", "192.168.1.5")).allMatch(peer -> trusted.matcher(peer).matches());
        assertThat(List.of("192.168.1.50", "192x168x1x5")).noneMatch(peer -> trusted.matcher(peer).matches());
    }

    /** Spring Boot's own forwarded-header support believes every private address, so a LAN client could fake one. */
    @Test
    void theyReplaceSpringsForwardedHeaderSupportWhichTrustsEveryClient() {
        List<String> proxy = List.of("192.168.1.5");
        MockEnvironment strategy = settings("server.forward-headers-strategy", "framework");
        MockEnvironment remoteIp = settings("server.tomcat.remoteip.remote-ip-header", "X-Real-IP");
        MockEnvironment protocol = settings("server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto");

        assertThatThrownBy(() -> new TrustedProxies(proxy, strategy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVER_FORWARD_HEADERS_STRATEGY");
        assertThatThrownBy(() -> new TrustedProxies(proxy, remoteIp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.tomcat.remoteip.remote-ip-header");
        assertThatThrownBy(() -> new TrustedProxies(proxy, protocol))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.tomcat.remoteip.protocol-header");
        new TrustedProxies(proxy, settings("server.forward-headers-strategy", "none"));
    }

    @Test
    void anInstallStillOnSpringsForwardedHeadersIsToldTheyAreBelievedFromEveryone(CapturedOutput output) {
        new TrustedProxies(List.of(), settings("server.forward-headers-strategy", "framework"));

        assertThat(output).contains("SERVER_FORWARD_HEADERS_STRATEGY").contains("HOME_CONTROL_TRUSTED_PROXIES");
    }

    @Test
    void theAppShipsWithSpringsForwardedHeaderSupportOff() throws Exception {
        String yaml = new String(TrustedProxiesTest.class.getResourceAsStream("/application.yaml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(yaml).contains("forward-headers-strategy: none");
    }

    private static MockEnvironment settings(String name, String value) {
        return new MockEnvironment().withProperty(name, value);
    }

    @Test
    void onlyAddressesMayBeTrustedNotNames() {
        List<String> none = List.of();
        Duration window = Duration.ofMinutes(15);
        List<String> named = List.of("proxy.lan");

        assertThatThrownBy(() -> new SecurityProperties(null, none, 5, 50, window, none, false, named))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.security.trusted-proxies").hasMessageContaining("proxy.lan");
    }
}
