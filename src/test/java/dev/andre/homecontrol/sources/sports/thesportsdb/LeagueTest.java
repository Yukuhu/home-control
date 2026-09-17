package dev.andre.homecontrol.sources.sports.thesportsdb;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LeagueTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode node(String json) {
        return mapper.readTree(json);
    }

    @Test
    void requiresIdAndName() {
        assertThat(League.of(node("{\"idLeague\":\"4a\",\"strLeague\":\"X\"}"))).isEmpty();
        assertThat(League.of(node("{\"idLeague\":\"1\",\"strLeague\":\"  \"}"))).isEmpty();
        assertThat(League.of(node("{\"idLeague\":\"1\",\"strLeague\":\"OK\"}"))).isPresent();
    }

    @Test
    void keepsOnlyHttpsBadges() {
        assertThat(League.of(node("{\"idLeague\":\"1\",\"strLeague\":\"X\",\"strBadge\":\"http://x/y.png\"}"))
                .orElseThrow().badge()).isNull();
        assertThat(League.of(node("{\"idLeague\":\"1\",\"strLeague\":\"X\",\"strBadge\":\"https://x/y.png\"}"))
                .orElseThrow().badge()).isNotNull();
    }

    @Test
    void stripsBlankSportAndCountry() {
        League league = League.of(node("{\"idLeague\":\"1\",\"strLeague\":\"X\",\"strSport\":\" \",\"strCountry\":\"\"}"))
                .orElseThrow();
        assertThat(league.sport()).isNull();
        assertThat(league.country()).isNull();
    }

    @Test
    void cutsLongNames() {
        String longName = "a".repeat(121);
        League league = League.of(node("{\"idLeague\":\"1\",\"strLeague\":\"" + longName + "\"}")).orElseThrow();
        assertThat(league.name()).hasSize(120);
    }
}
