package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.SourceRef;

/** Opaque workflow identity; never contains a resolved URL or credentials. */
public record WorkflowCastRef(String workflowId, long revision, String entryKey) implements SourceRef {
    @Override public String kindLabel() { return "workflow Cast"; }

    @Override public String unroutableReason() { return "this device is not a Cast receiver"; }
}
