package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Optional;

/** The speaker groups a device can see, from that device's point of view ({@code selfId}). */
public record SpeakerTopology(String selfId, List<SpeakerGroup> groups) {

    public SpeakerTopology {
        groups = List.copyOf(groups);
    }

    public Optional<SpeakerGroup> ownGroup() {
        return groups.stream().filter(group -> group.contains(selfId)).findFirst();
    }

    public List<SpeakerGroup> otherGroups() {
        return groups.stream().filter(group -> !group.contains(selfId)).toList();
    }

    public boolean grouped() {
        return ownGroup().map(group -> group.members().size() > 1).orElse(false);
    }
}
