package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behind a reverse proxy, only the proxies the owner names may say whom they forward for. */
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
                response.getWriter().write(request.getRemoteAddr() + " " + request.getScheme() + " " + request.isSecure()
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
        int port = serve(new TrustedProxies(List.of("127.0.0.1"), null));

        assertThat(seen(port, FORWARDED)).as("the rightmost address the proxy added, not one the browser sent")
                .isEqualTo("203.0.113.9 https true");
        assertThat(seen(port, Map.of())).isEqualTo("127.0.0.1 http false");
    }

    /** What the YouTube sign-in shows as its callback: the address the browser used, not the app's own. */
    @Test
    void aTrustedProxyNamesTheHostAndPortTheBrowserUsed() throws Exception {
        int port = serve(new TrustedProxies(List.of("127.0.0.1"), null));

        assertThat(seen(port, "/?where", Map.of("X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "home.example.org", "X-Forwarded-Port", "8443")))
                .isEqualTo("203.0.113.9 https true home.example.org:8443");
        assertThat(seen(port, "/?where", Map.of("X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "https")))
                .as("without them, the proxy's scheme's port and the Host the proxy sent")
                .isEqualTo("203.0.113.9 https true 127.0.0.1:443");
    }

    @Test
    void withoutTrustedProxiesNobodyMaySayWhomItForwardsFor() throws Exception {
        assertThat(seen(serve(new TrustedProxies(List.of(), null)), FORWARDED)).isEqualTo("127.0.0.1 http false");
    }

    @Test
    void anotherAddressThanTheTrustedProxyIsTakenAtItsWord() throws Exception {
        int port = serve(new TrustedProxies(List.of("192.0.2.1"), null));

        assertThat(seen(port, FORWARDED)).isEqualTo("127.0.0.1 http false");
    }

    @Test
    void addressesMatchExactlyInTheFormTomcatReportsThem() {
        String pattern = TrustedProxies.pattern(List.of("::1", "192.168.1.5"));

        assertThat("0:0:0:0:0:0:0:1").matches(pattern);
        assertThat("192.168.1.5").matches(pattern);
        assertThat("192.168.1.50").doesNotMatch(pattern);
        assertThat("192x168x1x5").doesNotMatch(pattern);
    }

    @Test
    void theyReplaceSpringsForwardedHeaderSupportWhichTrustsEveryClient() {
        assertThatThrownBy(() -> new TrustedProxies(List.of("192.168.1.5"), "framework"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVER_FORWARD_HEADERS_STRATEGY");
        new TrustedProxies(List.of("192.168.1.5"), "none");
        new TrustedProxies(List.of(), "framework");
    }

    @Test
    void onlyAddressesMayBeTrustedNotNames() {
        assertThatThrownBy(() -> new SecurityProperties(null, List.of(), 5, 50, Duration.ofMinutes(15), List.of(),
                false, List.of("proxy.lan"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("home-control.security.trusted-proxies").hasMessageContaining("proxy.lan");
    }
}
