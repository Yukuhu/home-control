package dev.andre.homecontrol.sources.workflows;

/** A stage-specific error whose message contains only locally authored safe context. */
public final class WorkflowException extends RuntimeException {
    public enum Stage { FETCH, PARSE, SELECT, MAP, BUILD, WORKFLOW }

    private final Stage stage;
    private final String detail;
    private final String call;

    public WorkflowException(Stage stage, String safeDetail) {
        this(stage, label(stage), safeDetail, null);
    }

    private WorkflowException(Stage stage, String context, String detail, String call) {
        super(context + ": " + detail);
        this.stage = stage;
        this.detail = detail;
        this.call = call;
    }

    public Stage stage() { return stage; }

    /** The message without its context, e.g. {@code server returned HTTP 404}. */
    public String detail() { return detail; }

    /** The call that failed, or null when the failure was not inside a call. */
    public String call() { return call; }

    /** The same failure, told as part of a call and, for a per-entry call, the entry's public title. */
    public WorkflowException inCall(String callName, String entryTitle) {
        if (call != null) return this;
        String context = "Call " + callName + (entryTitle == null ? "" : " \u00b7 entry \"" + entryTitle + "\"");
        return new WorkflowException(stage, context, detail, callName);
    }

    private static String label(Stage stage) {
        return switch (stage) {
            case FETCH -> "Fetch JSON";
            case PARSE -> "Parse JSON";
            case SELECT -> "Choose entries";
            case MAP -> "Map fields";
            case BUILD -> "Build media URL";
            case WORKFLOW -> "Workflow";
        };
    }
}
