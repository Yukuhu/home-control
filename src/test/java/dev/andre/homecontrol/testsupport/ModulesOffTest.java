package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.config.Module;
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
 * The one application context with every module in {@link Module} switched off. A module-switch test extends it and
 * adds nothing to the context (no Spring annotation, bean override or dynamic property; {@code SharedContextRulesTest}
 * checks it). It proves that its module leaves no bean, setup section, route or file behind, and the application still
 * starts without it.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ModulesOffTest {

    private static Path dataDir;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected MockMvc mockMvc;

    /** Inherited by every subclass, so they share one cache key: one data directory, and every module off. */
    @DynamicPropertySource
    static void everyModuleOff(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("modules-off");
        registry.add("home-control.data-dir", dataDir::toString);
        for (Module module : Module.values()) {
            registry.add(module.property(), () -> "false");
        }
    }

    /** The context's data directory, into which a switched-off module writes nothing. */
    protected static Path dataDir() {
        return dataDir;
    }
}
