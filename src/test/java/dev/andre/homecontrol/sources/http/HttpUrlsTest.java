package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.sources.http.HttpUrls.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One parser for every outbound link; the rules say what a caller accepts beyond the shared ones. */
class HttpUrlsTest {

    private static final HttpUrls.Rules LENIENT = new HttpUrls.Rules(true, true, true, false, 0);
    private static final HttpUrls.Rules STRICT = new HttpUrls.Rules(false, false, false, false, 64);
    private static final HttpUrls.Rules WEBCAL = new HttpUrls.Rules(true, true, true, true, 0);

    private static Problem problemOf(String raw, HttpUrls.Rules rules) {
        try {
            HttpUrls.parse(raw, rules);
            return null;
        } catch (HttpUrls.InvalidUrlException e) {
            return e.problem();
        }
    }

    @Test
    void acceptsPlainHttpAndHttps() {
        assertThat(HttpUrls.parse("https://media.example:8920/x?y=1#z", LENIENT))
                .isEqualTo(URI.create("https://media.example:8920/x?y=1#z"));
        assertThat(HttpUrls.parse("http://[::1]:8096", LENIENT).getHost()).isEqualTo("[::1]");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''                                  | MISSING",
            "'   '                               | MISSING",
            "not a url                           | SYNTAX",
            "ftp://media.example/x               | SCHEME",
            "mailto:someone@example.org          | SCHEME",
            "https://user:pass@media.example/x   | USER_INFO",
            "https:///x                          | HOST",
            "http://[fe80::1%25eth0]/x           | HOST",
            "http://media.example:0/x            | PORT",
            "http://media.example:70000/x        | PORT"})
    void refusesWhatNoOutboundLinkMayBe(String raw, Problem problem) {
        assertThat(problemOf(raw, LENIENT)).isEqualTo(problem);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://media.example/x?y=1         | QUERY",
            "https://media.example/x#top         | FRAGMENT",
            "https://media.example/a/../b        | DOT_SEGMENT",
            "https://media.example/./b           | DOT_SEGMENT"})
    void refusesWhatTheRulesDoNotAllow(String raw, Problem problem) {
        assertThat(problemOf(raw, STRICT)).isEqualTo(problem);
        assertThat(problemOf(raw, LENIENT)).isNull();
    }

    @Test
    void refusesALinkLongerThanTheRules() {
        assertThat(problemOf("https://media.example/" + "a".repeat(64), STRICT)).isEqualTo(Problem.TOO_LONG);
    }

    @Test
    void turnsWebcalIntoHttpsOnlyWhenTheRulesSaySo() {
        assertThat(HttpUrls.parse("webcal://cal.example/a.ics", WEBCAL)).isEqualTo(URI.create("https://cal.example/a.ics"));
        assertThat(HttpUrls.parse("WEBCALS://cal.example/a.ics", WEBCAL)).isEqualTo(URI.create("https://cal.example/a.ics"));
        assertThat(problemOf("webcal://cal.example/a.ics", LENIENT)).isEqualTo(Problem.SCHEME);
    }

    @Test
    void itsDefaultMessageSaysWhatToFix() {
        assertThatThrownBy(() -> HttpUrls.parse("ftp://media.example/x", LENIENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Use an http or https link");
    }
}
