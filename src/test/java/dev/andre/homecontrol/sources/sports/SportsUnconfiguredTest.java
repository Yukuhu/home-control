package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.testsupport.FullAppTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/** The module is on but nothing is configured yet: no I/O happens and no file is created. */
class SportsUnconfiguredTest extends FullAppTest {

    @Autowired
    ContentSources sources;

    @Test
    void theModuleIsPresentButUnavailable() {
        var source = sources.find("sports");
        assertThat(source).isPresent();
        assertThat(source.get().available()).isFalse();

        assertThat(Files.exists(dataDir().resolve("sports.json"))).isFalse();
    }
}
