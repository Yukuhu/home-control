package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.ZoneId;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Saving the household time zone from the setup page: every outcome redirects back to it. */
class SportsTimeZoneSetupTest {

    private SportsSettingsService settings;
    private SportsSetupController controller;
    private RedirectAttributesModelMap redirect;

    @BeforeEach
    void setUp() {
        settings = mock(SportsSettingsService.class);
        SportsTimeZones zones = mock(SportsTimeZones.class);
        given(zones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        controller = new SportsSetupController(mock(SportsCalendars.class), settings, zones);
        redirect = new RedirectAttributesModelMap();
    }

    private Map<String, Object> flash() {
        return Map.copyOf(redirect.getFlashAttributes());
    }

    @Test
    void aMissingTimeZoneClearsTheChoice() {
        assertThat(controller.timeZone(null, redirect)).isEqualTo("redirect:/setup#sports");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UnaryOperator<SportsSettings>> update = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(settings).update(update.capture());
        assertThat(update.getValue().apply(SportsSettings.empty().withTimeZone("Europe/London")).timeZone()).isNull();
        assertThat(flash()).containsEntry("sportsMessage",
                "Times are shown in Europe/Berlin (default)");
    }

    @Test
    void anUnknownTimeZoneIsNotSaved() {
        assertThat(controller.timeZone("Mars/Base", redirect)).isEqualTo("redirect:/setup#sports");

        verify(settings, never()).update(any());
        assertThat(flash()).containsEntry("sportsError", "Use a time zone such as Europe/Berlin");
    }

    @Test
    void aStorageFailureIsReported() {
        willThrow(new StorageException("disk full", null)).given(settings).update(any());

        assertThat(controller.timeZone(" Europe/London ", redirect)).isEqualTo("redirect:/setup#sports");

        assertThat(flash())
                .containsEntry("sportsError", "Could not save sports settings")
                .doesNotContainKey("sportsMessage");
    }
}
