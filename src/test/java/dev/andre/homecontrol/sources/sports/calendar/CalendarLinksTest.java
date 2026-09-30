package dev.andre.homecontrol.sources.sports.calendar;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The calendar links people add, and what the setup page tells them about a link it cannot use. */
class CalendarLinksTest {

    @Test
    void acceptsHttpHttpsAndWebcal() {
        assertThat(CalendarLinks.parse("https://calendar.example.org/a.ics"))
                .isEqualTo(URI.create("https://calendar.example.org/a.ics"));
        assertThat(CalendarLinks.parse("webcal://fixtur.es/de/team.ics?x=1"))
                .isEqualTo(URI.create("https://fixtur.es/de/team.ics?x=1"));
        assertThat(CalendarLinks.parse("WEBCALS://Example.org/x")).isEqualTo(URI.create("https://Example.org/x"));
        assertThat(CalendarLinks.parse("http://192.168.1.20:5232/user/sport/"))
                .isEqualTo(URI.create("http://192.168.1.20:5232/user/sport/"));
        assertThat(CalendarLinks.parse("  https://calendar.example.org/a.ics#today "))
                .isEqualTo(URI.create("https://calendar.example.org/a.ics#today"));
    }

    @Test
    void rejectsBadLinks() {
        assertThatThrownBy(() -> CalendarLinks.parse(" ")).hasMessage("Enter a calendar link");
        assertThatThrownBy(() -> CalendarLinks.parse("a".repeat(2049))).hasMessage("That calendar link is too long");
        assertThatThrownBy(() -> CalendarLinks.parse("ftp://example.org/a.ics")).hasMessage("Use an http, https or webcal link");
        assertThatThrownBy(() -> CalendarLinks.parse("https://user:pw@example.org/a.ics"))
                .hasMessage("Links with a user name or password are not supported; use the calendar's secret link instead");
        assertThatThrownBy(() -> CalendarLinks.parse("https:///a.ics")).hasMessage("That is not a valid link");
        assertThatThrownBy(() -> CalendarLinks.parse("not a url")).hasMessage("That is not a valid link");
        assertThatThrownBy(() -> CalendarLinks.parse("http://cal.example:0/a.ics")).hasMessage("That is not a valid link");
        assertThatThrownBy(() -> CalendarLinks.parse("http://[fe80::1%25eth0]/a.ics")).hasMessage("That is not a valid link");
    }
}
