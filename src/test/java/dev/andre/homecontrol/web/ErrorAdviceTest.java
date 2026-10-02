package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.security.LoginRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every core exception becomes one status and a plain-text body a toast can show as it is. */
class ErrorAdviceTest {

    private static final Map<String, Supplier<RuntimeException>> THROWN = Map.of(
            "not-found", () -> new DeviceNotFoundException("No device with id ghost"),
            "offline", () -> new DeviceOfflineException("Shield is offline"),
            "unsupported", () -> new UnsupportedActionException("Shield cannot switch inputs"),
            "unroutable", () -> new UnroutableException("Shield: this device cannot open app links"),
            "failed", () -> new ActionFailedException("Shield refused the key"),
            "source", () -> new ContentSourceException(ContentSourceException.Kind.UNREACHABLE, "Jellyfin did not answer"),
            "login", LoginRequiredException::new);

    @RestController
    static class Throwing {
        @GetMapping("/throw/{kind}")
        String fail(@PathVariable String kind) {
            throw THROWN.get(kind).get();
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Throwing())
            .setControllerAdvice(new ErrorAdvice()).build();

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of("not-found", 404, "No device with id ghost"),
                Arguments.of("offline", 409, "Shield is offline"),
                Arguments.of("unsupported", 422, "Shield cannot switch inputs"),
                Arguments.of("unroutable", 422, "Shield: this device cannot open app links"),
                Arguments.of("failed", 502, "Shield refused the key"),
                Arguments.of("source", 502, "Jellyfin did not answer"),
                Arguments.of("login", 401, "Log in first"));
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void eachCoreExceptionHasItsStatusAndAPlainTextBody(String kind, int status, String body) throws Exception {
        mockMvc.perform(get("/throw/" + kind).header("HX-Request", "true"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(body));
    }

    /** As the login gate does: htmx follows the header to the login page instead of showing the 401 in place. */
    @Test
    void aLoginRequiredInsideAnHtmxRequestSendsItToTheLoginPage() throws Exception {
        mockMvc.perform(get("/throw/login").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
        mockMvc.perform(get("/throw/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("HX-Redirect"));
    }
}
