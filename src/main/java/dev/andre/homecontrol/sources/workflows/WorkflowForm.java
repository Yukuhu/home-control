package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;

import java.util.ArrayList;
import java.util.List;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** MVC-only draft. Stored credentials never populate this object. */
public final class WorkflowForm {
    public enum Replacement { KEEP, REPLACE }
    public enum DisplaySource { NONE, POINTER, VARIABLE }

    public String name = "";
    public boolean enabled = true;
    public Mode mode = Mode.SINGLE;
    public ContentKind kind = ContentKind.VIDEO;
    public String title = "";
    public String subtitle = "";
    public String artwork = "";
    public List<CallRow> calls = new ArrayList<>();
    public String entryCall = "";
    public String arrayPointer = "";
    public String idPointer = "";
    public String titlePointer = "";
    public DisplaySource subtitleFrom = DisplaySource.NONE;
    public String subtitlePointer = "";
    public String subtitleVariable = "";
    public DisplaySource artworkFrom = DisplaySource.NONE;
    public String artworkPointer = "";
    public String artworkVariable = "";
    public List<VariableRow> entryVariables = new ArrayList<>();
    public Replacement templateMode = Replacement.REPLACE;
    public String template = "";
    public String mimeType = "video/mp4";
    public long expectedRevision;
    public String loginPassword = "";
    public String loginPasswordConfirmation = "";

    public static final class CallRow {
        public String name = "";
        public CallScope scope = CallScope.SHARED;
        /** The name this call was saved under; it finds the saved URL and headers for Keep. Empty for a new call. */
        public String savedName = "";
        public Replacement urlMode = Replacement.REPLACE;
        public String url = "";
        public Replacement headersMode = Replacement.REPLACE;
        public List<HeaderRow> headers = new ArrayList<>();
        public List<VariableRow> variables = new ArrayList<>();
        /** Shown, never bound: when the saved call runs. */
        public String phase = "";
    }

    public static final class VariableRow {
        public String name = "";
        public String pointer = "";
        public boolean sensitive = true;
    }

    public static final class HeaderRow {
        public String name = "";
        public String value = "";
    }

    /** A new workflow's editor starts with one shared call. */
    public static WorkflowForm blank() {
        WorkflowForm form = new WorkflowForm();
        CallRow call = new CallRow();
        call.name = WorkflowMigration.MAIN;
        form.calls.add(call);
        form.entryCall = call.name;
        return form;
    }

    public static WorkflowForm from(WorkflowDefinition saved) {
        WorkflowForm form = new WorkflowForm();
        WorkflowDraft d = saved.draft();
        WorkflowPlan plan = WorkflowPlan.of(d);
        form.name = d.name(); form.enabled = d.enabled(); form.mode = d.mode(); form.kind = d.kind();
        form.expectedRevision = saved.revision(); form.mimeType = d.cast().mimeType();
        form.templateMode = Replacement.KEEP;
        for (Call call : d.calls()) {
            CallRow row = new CallRow();
            row.name = row.savedName = call.name();
            row.scope = call.scope();
            row.urlMode = row.headersMode = Replacement.KEEP;
            // Header replacement starts empty; Keep retains the saved list in service memory.
            row.variables = rows(call.variables());
            row.phase = plan.phase(call.name()).label();
            form.calls.add(row);
        }
        if (d.tile() != null) {
            form.title = d.tile().title(); form.subtitle = text(d.tile().subtitle()); form.artwork = text(d.tile().artwork());
        }
        if (d.listing() != null) {
            Listing l = d.listing();
            form.entryCall = l.call();
            form.arrayPointer = l.arrayPointer(); form.idPointer = l.idPointer(); form.titlePointer = l.titlePointer();
            form.subtitleFrom = source(l.subtitle());
            form.subtitlePointer = l.subtitle() == null ? "" : text(l.subtitle().pointer());
            form.subtitleVariable = l.subtitle() == null ? "" : text(l.subtitle().variable());
            form.artworkFrom = source(l.artwork());
            form.artworkPointer = l.artwork() == null ? "" : text(l.artwork().pointer());
            form.artworkVariable = l.artwork() == null ? "" : text(l.artwork().variable());
            form.entryVariables = rows(l.variables());
        } else if (!d.calls().isEmpty()) {
            form.entryCall = d.calls().getFirst().name();
        }
        return form;
    }

    public WorkflowDraft toDraft(WorkflowDefinition saved) {
        if (templateMode == null || (saved == null && templateMode == Replacement.KEEP)) throw keepWithoutSaved();
        List<Call> built = calls.stream().map(row -> call(row, saved)).toList();
        String media = templateMode == Replacement.KEEP ? saved.draft().cast().template() : template;
        return new WorkflowDraft(name, enabled, mode, kind, built, listing(),
                mode == Mode.SINGLE ? new Tile(title, optional(subtitle), optional(artwork)) : null,
                new Cast(media, mimeType));
    }

    private static Call call(CallRow row, WorkflowDefinition saved) {
        if (row.urlMode == null || row.headersMode == null) throw keepWithoutSaved();
        boolean keeps = row.urlMode == Replacement.KEEP || row.headersMode == Replacement.KEEP;
        Call previous = keeps ? savedCall(saved, row.savedName) : null;
        String url = row.urlMode == Replacement.KEEP ? previous.url() : row.url;
        List<Header> headers = row.headersMode == Replacement.KEEP ? previous.headers()
                : row.headers.stream().map(h -> new Header(h.name, h.value)).toList();
        return new Call(row.name, row.scope, url, headers, variables(row.variables));
    }

    private static Call savedCall(WorkflowDefinition saved, String savedName) {
        if (saved == null || savedName == null || savedName.isEmpty()) throw keepWithoutSaved();
        return saved.draft().calls().stream().filter(call -> call.name().equals(savedName)).findFirst()
                .orElseThrow(WorkflowForm::keepWithoutSaved);
    }

    private static WorkflowException keepWithoutSaved() {
        return new WorkflowException(WorkflowException.Stage.WORKFLOW, "new workflows require replacement settings");
    }

    private Listing listing() {
        if (mode != Mode.GENERATED) return null;
        return new Listing(entryCall, arrayPointer, idPointer, titlePointer,
                field(subtitleFrom, subtitlePointer, subtitleVariable), field(artworkFrom, artworkPointer, artworkVariable),
                variables(entryVariables));
    }

    private static Field field(DisplaySource source, String pointer, String variable) {
        if (source == null || source == DisplaySource.NONE) return null;
        return source == DisplaySource.POINTER ? new Field(pointer, null) : new Field(null, variable);
    }

    private static DisplaySource source(Field field) {
        if (field == null) return DisplaySource.NONE;
        return field.pointer() != null ? DisplaySource.POINTER : DisplaySource.VARIABLE;
    }

    private static List<Variable> variables(List<VariableRow> rows) {
        return rows.stream().map(row -> new Variable(row.name, row.pointer, row.sensitive)).toList();
    }

    private static List<VariableRow> rows(List<Variable> variables) {
        List<VariableRow> rows = new ArrayList<>();
        for (Variable v : variables) {
            VariableRow row = new VariableRow();
            row.name = v.name(); row.pointer = v.pointer(); row.sensitive = v.sensitive();
            rows.add(row);
        }
        return rows;
    }

    public void clearSecrets() {
        template = loginPassword = loginPasswordConfirmation = "";
        for (CallRow call : calls) {
            call.url = "";
            call.headers.forEach(row -> row.value = "");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static String optional(String value) { return value == null || value.isEmpty() ? null : value; }
}
