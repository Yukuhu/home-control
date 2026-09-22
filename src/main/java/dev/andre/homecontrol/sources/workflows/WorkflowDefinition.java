package dev.andre.homecontrol.sources.workflows;

/** A stored workflow with an optimistic revision and explicit format version. */
public record WorkflowDefinition(int schemaVersion, String id, long revision, WorkflowDraft draft) {
    @Override public String toString() {
        return "WorkflowDefinition[id=" + id + ", revision=" + revision + "]";
    }
}
