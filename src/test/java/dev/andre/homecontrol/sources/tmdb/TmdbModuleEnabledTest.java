package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.testsupport.FullAppTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** The default ({@code home-control.tmdb.enabled} unset, so {@code true}): the module is wired up. */
class TmdbModuleEnabledTest extends FullAppTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ContentSources sources;

    @Test
    void theModuleIsWiredUpByDefault() {
        assertThat(context.getBeanNamesForType(TmdbContentSource.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(TmdbSetupController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(TmdbSetupAdvice.class)).isNotEmpty();
        assertThat(sources.find("tmdb")).isPresent();
    }
}
