package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerTopologyTest {

    private static final SpeakerGroup LIVING = new SpeakerGroup("L",
            List.of(new GroupMember("L", "Living Room"), new GroupMember("K", "Kitchen")));
    private static final SpeakerGroup OFFICE = new SpeakerGroup("O", List.of(new GroupMember("O", "Office")));

    @Test
    void findsTheOwnGroupAndTheOthers() {
        SpeakerTopology topology = new SpeakerTopology("K", List.of(LIVING, OFFICE));

        assertThat(topology.ownGroup()).contains(LIVING);
        assertThat(topology.otherGroups()).containsExactly(OFFICE);
        assertThat(topology.grouped()).isTrue();
        assertThat(LIVING.label()).isEqualTo("Living Room + Kitchen");
    }

    @Test
    void aSpeakerAloneIsNotGrouped() {
        SpeakerTopology topology = new SpeakerTopology("O", List.of(LIVING, OFFICE));

        assertThat(topology.grouped()).isFalse();
        assertThat(topology.otherGroups()).containsExactly(LIVING);
    }

    @Test
    void anUnknownSelfHasNoOwnGroup() {
        SpeakerTopology topology = new SpeakerTopology("X", List.of(LIVING, OFFICE));

        assertThat(topology.ownGroup()).isEmpty();
        assertThat(topology.otherGroups()).containsExactly(LIVING, OFFICE);
    }
}
