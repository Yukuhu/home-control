package dev.andre.homecontrol.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one application context with every module that can be switched off switched off; Android TV, which cannot,
 * stays. A module-switch test extends it and adds nothing to the context (no Spring annotation, bean override or
 * dynamic property; {@code SharedContextRulesTest} checks it). It proves that its module leaves no bean, setup section,
 * route or file behind, and the application still starts without it.
 */
@SpringBootTest(properties = {"home-control.jellyfin.enabled=false", "home-control.youtube.enabled=false",
        "home-control.tmdb.enabled=false", "home-control.pinned.enabled=false", "home-control.sports.enabled=false",
        "home-control.workflows.enabled=false", "home-control.cast.enabled=false", "home-control.webos.enabled=false",
        "home-control.tizen.enabled=false", "home-control.upnp.enabled=false", "home-control.sonos.enabled=false",
        "home-control.bluetooth.enabled=false"})
@AutoConfigureMockMvc
public abstract class ModulesOffTest {

    private static Path dataDir;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected MockMvc mockMvc;

    /** Inherited by every subclass, so they share one cache key: one data directory per context. */
    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("modules-off");
        registry.add("shield.data-dir", dataDir::toString);
    }

    /** The context's data directory, into which a switched-off module writes nothing. */
    protected static Path dataDir() {
        return dataDir;
    }
}
