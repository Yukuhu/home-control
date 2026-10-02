package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.storage.DataDirectory;
import dev.andre.homecontrol.testsupport.FullAppTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module is on but nothing is configured yet. The application starts without writing sports.json; an earlier test
 * class may have written it since, so the rest checks the settings, not the file.
 */
class SportsUnconfiguredTest extends FullAppTest {

    @Autowired
    ContentSources sources;

    @Autowired
    SportsSettingsService settings;

    @Test
    void theModuleIsPresentButUnavailable() {
        var source = sources.find("sports");
        assertThat(source).isPresent();
        assertThat(source.get().available()).isFalse();

        assertThat(settings.current()).isEqualTo(SportsSettings.empty());
    }

    @Test
    void theApplicationStartsWithoutWritingTheSportsSettings() {
        // Startup writes nothing to the data directory, so the listing is empty: ask about the one file directly.
        assertThat(filesAtStartup().contains(DataDirectory.SPORTS)).as("files at startup: %s", filesAtStartup()).isFalse();
    }
}
