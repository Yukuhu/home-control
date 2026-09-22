package dev.andre.homecontrol.sources.workflows;

/** A stage-specific error whose message contains only locally authored safe context. */
public final class WorkflowException extends RuntimeException {
    public enum Stage { FETCH, PARSE, SELECT, MAP, BUILD, WORKFLOW }

    private final Stage stage;

    public WorkflowException(Stage stage, String safeDetail) {
        super(label(stage) + ": " + safeDetail);
        this.stage = stage;
    }

    public Stage stage() { return stage; }

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
