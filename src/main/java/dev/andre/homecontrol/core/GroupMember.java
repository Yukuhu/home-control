package dev.andre.homecontrol.core;

/** One speaker in a {@link SpeakerGroup}: an adapter-specific id (a Sonos RINCON id) and its room name. */
public record GroupMember(String memberId, String name) {
}
