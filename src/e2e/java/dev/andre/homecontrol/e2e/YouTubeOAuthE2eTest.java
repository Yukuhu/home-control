package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import dev.andre.homecontrol.sources.youtube.FakeGoogleServer;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** A real browser leaves Home Control for a simulated Google page and returns with its session cookie. */
class YouTubeOAuthE2eTest extends E2eApplicationTest {
    static final FakeGoogleServer GOOGLE;
    static {
        try {
            GOOGLE = new FakeGoogleServer();
            GOOGLE.youtubeLibrary();
            GOOGLE.respond("POST", "/oauth/token", FakeGoogleServer.Canned.fixture(200, "oauth-token-granted.json"));
            GOOGLE.respond("POST", "/oauth/revoke", FakeGoogleServer.Canned.json(200, "{}"));
            GOOGLE.respond("GET", "/consent", new FakeGoogleServer.Canned(200, "text/html", """
                    <a id="allow">Allow YouTube access</a>
                    <script>
                      const params = new URLSearchParams(location.search);
                      const callback = new URL(params.get('redirect_uri'));
                      callback.searchParams.set('code', 'browser-code');
                      callback.searchParams.set('state', params.get('state'));
                      document.getElementById('allow').href = callback;
                    </script>
                    """.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        registry.add("home-control.youtube.oauth-base-url", () -> GOOGLE.base() + "/oauth");
        registry.add("home-control.youtube.api-base-url", () -> GOOGLE.base() + "/youtube/v3");
        registry.add("home-control.content.rails.scheduler-enabled", () -> "false");
    }

    @Autowired YouTubeSetupService youtube;

    @AfterEach
    void disconnectYouTube() { youtube.disconnect(); }

    @AfterAll
    static void closeGoogle() { GOOGLE.close(); }

    @BrowserTest
    void setupButtonOpensConsentAndTheCallbackKeepsTheHouseholdLoggedIn(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            // Inspect the real app's 302 and preserve its session cookie. WebKit cannot fulfill
            // an intercepted redirect, so a temporary HTML hop takes the browser to our consent
            // server (127.0.0.1, a different site from localhost) without reaching real Google.
            page.route("**/setup/sources/youtube/browser/connect", route -> {
                var response = route.fetch(new Route.FetchOptions().setMaxRedirects(0));
                org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo(302);
                URI request = URI.create(response.headers().get("location"));
                org.assertj.core.api.Assertions.assertThat(request.getHost()).isEqualTo("accounts.google.com");
                Map<String, String> query = Arrays.stream(request.getRawQuery().split("&"))
                        .map(part -> part.split("=", 2)).collect(Collectors.toMap(p -> p[0],
                                p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
                org.assertj.core.api.Assertions.assertThat(query).containsEntry("response_type", "code")
                        .containsEntry("redirect_uri", baseUrl() + "/setup/sources/youtube/callback");
                var headers = new HashMap<>(response.headers());
                headers.remove("location");
                headers.remove("content-length");
                String consentUrl = GOOGLE.base() + "/consent?" + request.getRawQuery();
                route.fulfill(new Route.FulfillOptions().setResponse(response).setStatus(200)
                        .setHeaders(headers).setContentType("text/html")
                        .setBody("<meta http-equiv=\"refresh\" content=\"0;url="
                                + HtmlUtils.htmlEscape(consentUrl) + "\">"));
            });
            page.navigate("/setup");
            var section = page.locator("#youtube");
            assertThat(section.locator("code")).hasText(baseUrl() + "/setup/sources/youtube/callback");
            section.locator("input[name=clientId]").fill("123456789012-abc123def456.apps.googleusercontent.com");
            section.locator("input[name=clientSecret]").fill("GOCSPX-fixtureClientSecret");
            section.locator("input[name=loginPassword]").fill("correct-horse-1");
            section.locator("input[name=loginPasswordConfirmation]").fill("correct-horse-1");
            section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Sign in with Google").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Allow YouTube access"))).isVisible();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Allow YouTube access")).click();
            assertThat(page).hasURL(baseUrl() + "/setup#youtube");
            assertThat(page.locator("#youtube")).containsText("Connected as Andre at Home");
            org.assertj.core.api.Assertions.assertThat(page.content()).doesNotContain(
                    "GOCSPX-fixtureClientSecret", "1//0gFixture", "ya29.", "browser-code");
        }
    }
}
