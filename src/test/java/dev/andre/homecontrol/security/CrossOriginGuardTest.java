package dev.andre.homecontrol.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossOriginGuardTest {

    private final CrossOriginGuard guard = new CrossOriginGuard(List.of("https://home.example.org"));

    private static MockHttpServletRequest request(String method, String... headers) {
        return requestTo(method, "/setup/sources/jellyfin", headers);
    }

    private static MockHttpServletRequest requestTo(String method, String uri, String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Host", "192.168.1.10:8080");
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    @Test
    void safeMethodsAlwaysPass() {
        assertThat(guard.allows(request("GET", "Origin", "http://evil.example"))).isTrue();
        assertThat(guard.allows(request("HEAD", "Sec-Fetch-Site", "cross-site"))).isTrue();
    }

    @Test
    void fetchMetadataDecidesWhenPresent() {
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "same-origin"))).isTrue();
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "none"))).isTrue();
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "same-site", "Origin", "http://192.168.1.10:9000"))).isFalse();
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "cross-site", "Origin", "http://evil.example"))).isFalse();
    }

    @Test
    void withoutFetchMetadataTheOriginMustMatchTheHost() {
        assertThat(guard.allows(request("POST", "Origin", "http://192.168.1.10:8080"))).isTrue();
        assertThat(guard.allows(request("POST", "Origin", "http://192.168.1.10:9000"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "null"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "not a uri"))).isFalse();
    }

    @Test
    void requestsWithNeitherHeaderAreNotFromABrowserAndPass() {
        assertThat(guard.allows(request("POST"))).isTrue();
    }

    @Test
    void trustedOriginsPassEvenWhenAProxyRewroteHost() {
        assertThat(guard.allows(request("POST", "Origin", "https://home.example.org"))).isTrue();
        assertThat(guard.allows(request("POST", "Origin", "https://home.example.org", "Sec-Fetch-Site", "cross-site"))).isTrue();
        assertThat(guard.allows(request("DELETE", "Origin", "https://other.example.org"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "PROPFIND", "post", "get"})
    void everyMethodButGetHeadAndOptionsIsChecked(String method) {
        assertThat(guard.allows(request(method, "Sec-Fetch-Site", "cross-site"))).isFalse();
        assertThat(guard.allows(request(method, "Origin", "http://evil.example"))).isFalse();
        assertThat(guard.allows(request(method, "Sec-Fetch-Site", "same-origin"))).isTrue();
        assertThat(guard.allows(request(method, "Origin", "http://192.168.1.10:8080"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/devices/abc/key/HOME", "/devices;x/abc/key/HOME", "/devices/abc;jsessionid=1/key/HOME",
            "/%64evices/abc/key/HOME", "/devices/%61bc/key/HOME", "/login", "/anything/else"})
    void theDecisionNeverDependsOnThePath(String uri) {
        assertThat(guard.allows(requestTo("POST", uri, "Sec-Fetch-Site", "cross-site", "Origin", "http://evil.example"))).isFalse();
        assertThat(guard.allows(requestTo("POST", uri, "Origin", "http://evil.example"))).isFalse();
        assertThat(guard.allows(requestTo("POST", uri, "Sec-Fetch-Site", "same-origin", "Origin", "http://192.168.1.10:8080"))).isTrue();
        assertThat(guard.allows(requestTo("POST", uri, "Origin", "http://192.168.1.10:8080"))).isTrue();
    }

    @Test
    void anOriginMustBeABareSchemeAndAuthority() {
        assertThat(guard.allows(request("POST", "Origin", "HTTP://192.168.1.10:8080"))).isTrue();
        assertThat(guard.allows(request("POST", "Origin", "http://evil@192.168.1.10:8080"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "http://192.168.1.10:8080/path"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "file://192.168.1.10:8080"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "http://192.168.1.10:8080.evil.example"))).isFalse();
    }

    @Test
    void withoutAHostHeaderTheServerNameAndPortAreTheHost() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/devices/abc/key/HOME");
        request.setServerName("tv.local");
        request.setServerPort(8080);
        request.addHeader("Origin", "http://tv.local:8080");
        assertThat(guard.allows(request)).isTrue();

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/devices/abc/key/HOME");
        other.setServerName("tv.local");
        other.setServerPort(8080);
        other.addHeader("Origin", "http://tv.local:9090");
        assertThat(guard.allows(other)).isFalse();
    }
}
