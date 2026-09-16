package dev.andre.homecontrol.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossOriginFilterTest {

    private final CrossOriginFilter filter = new CrossOriginFilter(new CrossOriginGuard(List.of()),
            new HostAllowlist(List.of(), List.of("home.example.org")));

    private static MockHttpServletRequest request(String method, String uri, String... headers) {
        return withHost("192.168.1.10:8080", method, uri, headers);
    }

    private static MockHttpServletRequest withHost(String host, String method, String uri, String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Host", host);
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    private static MockFilterChain run(CrossOriginFilter filter, MockHttpServletRequest request,
                                       MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/abc/key/HOME", "/devices;x/abc/key/HOME", "/%64evices/abc/key/HOME",
            "/setup/forget", "/setup;a=b/forget", "/login", "/"})
    void refusesACrossSiteRequestOnAnyPath(String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter, request("POST", uri, "Sec-Fetch-Site", "cross-site"), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/abc/key/HOME", "/devices;x/abc/key/HOME", "/%64evices/abc/key/HOME"})
    void refusesAMismatchedOriginWithoutFetchMetadata(String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter, request("DELETE", uri, "Origin", "http://evil.example"), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/abc/key/HOME", "/devices;x/abc/key/HOME", "/%64evices/abc/key/HOME"})
    void letsSameOriginAndMatchingOriginRequestsThrough(String uri) throws Exception {
        MockHttpServletResponse sameOrigin = new MockHttpServletResponse();
        assertThat(run(filter, request("POST", uri, "Sec-Fetch-Site", "same-origin"), sameOrigin).getRequest()).isNotNull();

        MockHttpServletResponse matchingOrigin = new MockHttpServletResponse();
        assertThat(run(filter, request("PUT", uri, "Origin", "http://192.168.1.10:8080"), matchingOrigin).getRequest()).isNotNull();
    }

    /**
     * DNS rebinding: evil.example first resolves to the attacker, then to this server. The
     * browser then treats the page and this app as one origin, so Origin matches Host and
     * Sec-Fetch-Site says same-origin — only the Host name itself gives it away.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "POST", "DELETE"})
    void refusesARebindingHostOnEveryMethodWithoutEchoingIt(String method) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter, withHost("evil.example:8080", method, "/setup/sources/jellyfin",
                "Origin", "http://evil.example:8080", "Sec-Fetch-Site", "same-origin"), response);

        assertThat(response.getStatus()).isEqualTo(421);
        assertThat(response.getContentAsString()).doesNotContain("evil");
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[::1]:8080", "[fd00::5]", "192.168.1.10", "tv.local:8080", "nas", "home.example.org"})
    void letsAllowedHostsThrough(String host) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter, withHost(host, "GET", "/"), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theHostCheckComesBeforeTheOriginCheck() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(filter, withHost("evil.example", "POST", "/devices/abc/key/HOME", "Sec-Fetch-Site", "cross-site"), response);

        assertThat(response.getStatus()).isEqualTo(421);
    }
}
