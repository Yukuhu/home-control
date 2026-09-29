package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Reads a schema version 1 definition and turns it into version 2: its one fetch becomes the shared call "main". */
final class WorkflowMigration {
    static final String MAIN = "main";

    private WorkflowMigration() {}

    record V1Definition(int schemaVersion, String id, long revision, V1Draft draft) {
        @Override public String toString() { return "V1Definition"; }
    }

    record V1Draft(String name, boolean enabled, Mode mode, ContentKind kind, V1Fetch fetch, V1Listing listing,
                   Tile tile, List<V1Variable> variables, Cast cast) {
        @Override public String toString() { return "V1Draft"; }
    }

    record V1Fetch(String url, List<Header> headers) {
        @Override public String toString() { return "V1Fetch"; }
    }

    record V1Listing(String arrayPointer, String idPointer, String titlePointer, String subtitlePointer,
                     String artworkPointer) {
        @Override public String toString() { return "V1Listing"; }
    }

    record V1Variable(String name, String scope, String pointer, boolean sensitive) {
        @Override public String toString() { return "V1Variable"; }
    }

    static WorkflowDefinition toV2(V1Definition v1) {
        if (v1 == null || v1.draft() == null || v1.draft().fetch() == null || v1.draft().variables() == null
                || v1.draft().fetch().headers() == null || v1.draft().fetch().headers().contains(null)) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "definition could not be parsed");
        }
        V1Draft d = v1.draft();
        // v1 header values were literal text; in v2 braces are template syntax, so double them.
        List<Header> headers = d.fetch().headers().stream()
                .map(h -> new Header(h.name(), h.value() == null ? null : WorkflowHeaderTemplate.literal(h.value())))
                .toList();
        Call main = new Call(MAIN, CallScope.SHARED, d.fetch().url(), headers, variables(d.variables(), "ROOT"));
        Listing listing = d.listing() == null ? null : new Listing(MAIN, d.listing().arrayPointer(),
                d.listing().idPointer(), d.listing().titlePointer(), pointer(d.listing().subtitlePointer()),
                pointer(d.listing().artworkPointer()), variables(d.variables(), "ENTRY"));
        return new WorkflowDefinition(WorkflowDefinition.SCHEMA_VERSION, v1.id(), v1.revision(),
                new WorkflowDraft(d.name(), d.enabled(), d.mode(), d.kind(), List.of(main), listing, d.tile(), d.cast()));
    }

    private static List<Variable> variables(List<V1Variable> all, String scope) {
        return all.stream().filter(v -> v != null && scope.equals(v.scope()))
                .map(v -> new Variable(v.name(), v.pointer(), v.sensitive())).toList();
    }

    private static Field pointer(String pointer) {
        return pointer == null ? null : new Field(pointer, null);
    }
}
