package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.DelegatedRoute;

/** Deferred workflow execution, available only to Cast receivers; {@link WorkflowCastRouteExecutor} runs it. */
public record WorkflowCastRoute(String workflowId, long revision, String entryKey) implements DelegatedRoute {

    /** The key {@link WorkflowCastRouteExecutor} is found by. */
    public static final String KEY = "workflow-cast";

    @Override public String source() { return "Workflows"; }

    @Override public String key() { return KEY; }

    @Override public String describe() { return "Cast with the Default Media Receiver"; }
}
