package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaunchedMediaTest {

    @Test
    void liveMediaHasNoDuration() {
        LaunchedMedia live = new LaunchedMedia("org.videolan.vlc", "Match day", null);

        assertThat(live.durationSeconds()).isNull();
        assertThat(live.title()).isEqualTo("Match day");
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL, Film", "' ', Film", "org.videolan.vlc, NULL", "org.videolan.vlc, ' '"},
            nullValues = "NULL")
    void needsAnAppPackageAndATitle(String appPackage, String title) {
        assertThatThrownBy(() -> new LaunchedMedia(appPackage, title, 90.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Launched media needs an app package and a title");
    }
}
