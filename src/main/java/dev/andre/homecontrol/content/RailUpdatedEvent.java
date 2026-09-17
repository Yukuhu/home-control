package dev.andre.homecontrol.content;

/** Published whenever one rail's cached snapshot changes (a fetch started, succeeded or failed). */
public record RailUpdatedEvent(RailSnapshot snapshot) {
}
