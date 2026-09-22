package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The editable settings for one fetch, JSON selection, and Cast action. */
public record WorkflowDraft(String name, boolean enabled, Mode mode, ContentKind kind,
                            Fetch fetch, Listing listing, Tile tile,
                            List<Variable> variables, Cast cast) {
    public enum Mode { SINGLE, GENERATED }
    public enum Scope { ROOT, ENTRY }

    public WorkflowDraft {
        variables = variables == null ? null : Collections.unmodifiableList(new ArrayList<>(variables));
    }

    @Override public String toString() {
        return "WorkflowDraft[mode=" + mode + "]";
    }

    public record Header(String name, String value) {
        @Override public String toString() { return "Header"; }
    }

    public record Fetch(String url, List<Header> headers) {
        public Fetch {
            headers = headers == null ? null : Collections.unmodifiableList(new ArrayList<>(headers));
        }

        @Override public String toString() { return "Fetch"; }
    }

    public record Listing(String arrayPointer, String idPointer, String titlePointer,
                          String subtitlePointer, String artworkPointer) {
        @Override public String toString() { return "Listing"; }
    }

    public record Tile(String title, String subtitle, String artwork) {
        @Override public String toString() { return "Tile"; }
    }

    public record Variable(String name, Scope scope, String pointer, boolean sensitive) {
        @Override public String toString() { return "Variable"; }
    }

    public record Cast(String template, String mimeType) {
        @Override public String toString() { return "Cast"; }
    }
}
