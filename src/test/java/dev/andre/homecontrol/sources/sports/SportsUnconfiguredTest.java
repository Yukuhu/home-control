package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.sources.sports.settings.SportsSettingsService;
import dev.andre.homecontrol.testsupport.FullAppTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module is on but nothing is configured yet. The shared application may have written sports.json for an earlier
 * test class and reset it to empty since, so this checks the settings, not the file.
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
}
