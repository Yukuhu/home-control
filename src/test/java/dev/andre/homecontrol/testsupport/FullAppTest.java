package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.sources.workflows.WorkflowHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one full-application context the end-to-end tests share: every module that is on by default, a real port,
 * MockMvc, a data directory made for this context, and the web APIs pointed at {@link SharedFakes}.
 * {@link FullAppReset} brings it back to a fresh install after every test class. A test class extends it and adds
 * nothing to the context ({@code SharedContextRulesTest}); a test that needs different beans or properties keeps a
 * context of its own and is listed there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(FullAppReset.class)
public abstract class FullAppTest {

    private static Path dataDir;
    private static ApplicationContext started;
    private static Set<String> filesAtStartup;

    /** A spy calls the real client; {@code WorkflowSetupAuthenticationTest} checks it was never used. */
    @MockitoSpyBean
    protected WorkflowHttpClient workflowHttp;

    /** Inherited by every subclass, so they share one cache key: the union of what the shared tests need. */
    @DynamicPropertySource
    static void sharedApplication(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("full-app");
        registry.add("home-control.data-dir", dataDir::toString);
        registry.add("home-control.security.allowed-hosts", () -> "tv.example.org, *.home.example.net");
        registry.add("home-control.cast.command-timeout", () -> "3s");
        registry.add("home-control.cast.load-timeout", () -> "5s");
        registry.add("home-control.upnp.poll-interval", () -> "1s");
        registry.add("home-control.upnp.idle-poll-interval", () -> "1s");
        registry.add("home-control.sports.calendar.allow-loopback", () -> "true");
        registry.add("home-control.sports.thesportsdb.api-base-url", () -> SharedFakes.theSportsDb().apiBase().toString());
        registry.add("home-control.tmdb.api-base-url", () -> SharedFakes.tmdb().apiBase().toString());
        registry.add("home-control.youtube.oauth-base-url", () -> SharedFakes.google().base() + "/oauth");
        registry.add("home-control.youtube.api-base-url", () -> SharedFakes.google().base() + "/youtube/v3");
        registry.add("home-control.youtube.lounge-base-url", () -> SharedFakes.google().base() + "/lounge");
        registry.add("home-control.youtube.thumbnail-base-url", () -> SharedFakes.google().base() + "/thumbs");
    }

    /** Before the first test class that uses a newly started context: its data directory's files, as startup left
     * them. */
    @BeforeAll
    static void noteTheFilesAtStartup(ApplicationContext context) throws IOException {
        if (context != started) {
            started = context;
            try (Stream<Path> files = Files.list(dataDir)) {
                filesAtStartup = files.map(file -> file.getFileName().toString())
                        .collect(Collectors.toUnmodifiableSet());
            }
        }
    }

    /** This context's data directory. */
    protected static Path dataDir() {
        return dataDir;
    }

    /** The names of the files in this context's data directory once the application had started. */
    protected static Set<String> filesAtStartup() {
        return filesAtStartup;
    }
}
