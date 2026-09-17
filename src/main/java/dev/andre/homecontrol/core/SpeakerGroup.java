package dev.andre.homecontrol.core;

import java.util.List;
import java.util.stream.Collectors;

/** Speakers playing in sync; the coordinator is listed first. */
public record SpeakerGroup(String coordinatorId, List<GroupMember> members) {

    public SpeakerGroup {
        members = List.copyOf(members);
    }

    public boolean contains(String memberId) {
        return members.stream().anyMatch(member -> member.memberId().equals(memberId));
    }

    public String label() {
        return members.stream().map(GroupMember::name).collect(Collectors.joining(" + "));
    }
}
