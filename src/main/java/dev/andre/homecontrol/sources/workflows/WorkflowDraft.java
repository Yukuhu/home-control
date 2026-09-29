package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A workflow's editable settings: its calls, where tiles come from, and the Cast action. Schema version 2. */
public record WorkflowDraft(String name, boolean enabled, Mode mode, ContentKind kind,
                            List<Call> calls, Listing listing, Tile tile, Cast cast) {
    public enum Mode { SINGLE, GENERATED }
    public enum CallScope { SHARED, ENTRY }

    public WorkflowDraft {
        calls = copy(calls);
    }

    public WorkflowDraft withEnabled(boolean enabled) {
        return new WorkflowDraft(name, enabled, mode, kind, calls, listing, tile, cast);
    }

    @Override public String toString() {
        return "WorkflowDraft[mode=" + mode + "]";
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** A header value is a {@link WorkflowHeaderTemplate}. */
    public record Header(String name, String value) {
        @Override public String toString() { return "Header"; }
    }

    /** One value read from a JSON response. */
    public record Variable(String name, String pointer, boolean sensitive) {
        @Override public String toString() { return "Variable"; }
    }

    /** One GET. Its URL is a call-URL {@link WorkflowTemplate}; its variables read its response. */
    public record Call(String name, CallScope scope, String url, List<Header> headers, List<Variable> variables) {
        public Call {
            headers = copy(headers);
            variables = copy(variables);
        }

        @Override public String toString() { return "Call[" + name + "]"; }
    }

    /**
     * Where generated tiles come from: an array in one shared call's response. Its pointers read each entry, and
     * its variables are the entry values.
     */
    public record Listing(String call, String arrayPointer, String idPointer, String titlePointer,
                          Field subtitle, Field artwork, List<Variable> variables) {
        public Listing {
            variables = copy(variables);
        }

        @Override public String toString() { return "Listing"; }
    }

    /** A generated tile's subtitle or artwork: read from the entry by pointer, or taken from a variable. */
    public record Field(String pointer, String variable) {}

    public record Tile(String title, String subtitle, String artwork) {
        @Override public String toString() { return "Tile"; }
    }

    public record Cast(String template, String mimeType) {
        @Override public String toString() { return "Cast"; }
    }
}
