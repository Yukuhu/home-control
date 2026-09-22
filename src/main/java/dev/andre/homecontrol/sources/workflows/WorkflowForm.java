package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentKind;
import java.util.ArrayList;
import java.util.List;
import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** MVC-only draft. Stored credentials never populate this object. */
public final class WorkflowForm {
    public enum Replacement { KEEP, REPLACE }
    public String name = "";
    public boolean enabled = true;
    public Mode mode = Mode.SINGLE;
    public ContentKind kind = ContentKind.VIDEO;
    public String title = "", subtitle = "", artwork = "";
    public String arrayPointer = "", idPointer = "", titlePointer = "", subtitlePointer = "", artworkPointer = "";
    public boolean includeSubtitlePointer, includeArtworkPointer;
    public List<VariableRow> variables = new ArrayList<>();
    public Replacement urlMode = Replacement.REPLACE, templateMode = Replacement.REPLACE, headersMode = Replacement.REPLACE;
    public String url = "", template = "", mimeType = "video/mp4";
    public List<HeaderRow> headers = new ArrayList<>();
    public long expectedRevision;
    public String loginPassword = "", loginPasswordConfirmation = "";

    public static final class VariableRow {
        public String name = "", pointer = "";
        public Scope scope = Scope.ROOT;
        public boolean sensitive = true;
    }
    public static final class HeaderRow { public String name = "", value = ""; }

    public static WorkflowForm from(WorkflowDefinition saved) {
        WorkflowForm form = new WorkflowForm();
        WorkflowDraft d = saved.draft();
        form.name = d.name(); form.enabled = d.enabled(); form.mode = d.mode(); form.kind = d.kind();
        form.expectedRevision = saved.revision(); form.mimeType = d.cast().mimeType();
        form.urlMode = form.templateMode = form.headersMode = Replacement.KEEP;
        if (d.tile() != null) {
            form.title = d.tile().title(); form.subtitle = text(d.tile().subtitle()); form.artwork = text(d.tile().artwork());
        }
        if (d.listing() != null) {
            Listing l = d.listing();
            form.arrayPointer = l.arrayPointer(); form.idPointer = l.idPointer(); form.titlePointer = l.titlePointer();
            form.includeSubtitlePointer = l.subtitlePointer() != null;
            form.includeArtworkPointer = l.artworkPointer() != null;
            form.subtitlePointer = text(l.subtitlePointer()); form.artworkPointer = text(l.artworkPointer());
        }
        for (Variable v : d.variables()) {
            VariableRow row = new VariableRow();
            row.name = v.name(); row.scope = v.scope(); row.pointer = v.pointer(); row.sensitive = v.sensitive();
            form.variables.add(row);
        }
        // Header replacement starts empty; Keep retains the entire saved list in service memory.
        return form;
    }

    public WorkflowDraft toDraft(WorkflowDefinition saved) {
        if (urlMode == null || templateMode == null || headersMode == null
                || saved == null && (urlMode == Replacement.KEEP || templateMode == Replacement.KEEP || headersMode == Replacement.KEEP)) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "new workflows require replacement settings");
        }
        String source = urlMode == Replacement.KEEP ? saved.draft().fetch().url() : url;
        String media = templateMode == Replacement.KEEP ? saved.draft().cast().template() : template;
        List<Header> requestHeaders = headersMode == Replacement.KEEP ? saved.draft().fetch().headers()
                : headers.stream().map(row -> new Header(row.name, row.value)).toList();
        return new WorkflowDraft(name, enabled, mode, kind, new Fetch(source, requestHeaders),
                mode == Mode.GENERATED ? new Listing(arrayPointer, idPointer, titlePointer,
                        includeSubtitlePointer ? subtitlePointer : null, includeArtworkPointer ? artworkPointer : null) : null,
                mode == Mode.SINGLE ? new Tile(title, optional(subtitle), optional(artwork)) : null,
                variables.stream().map(row -> new Variable(row.name, row.scope, row.pointer, row.sensitive)).toList(),
                new Cast(media, mimeType));
    }

    public void clearSecrets() {
        url = template = loginPassword = loginPasswordConfirmation = "";
        headers.forEach(row -> row.value = "");
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static String optional(String value) { return value == null || value.isEmpty() ? null : value; }
}
