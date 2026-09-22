package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.security.LoginRequiredException;
import dev.andre.homecontrol.security.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** Explicit, authenticated preview. Only the returned display strings may leave this service. */
@Service
@ConditionalOnProperty(name = "home-control.workflows.enabled", havingValue = "true", matchIfMissing = true)
public final class WorkflowTestService {
    public record StageView(String name, boolean success, String message) {}
    public record SampleView(String title, String subtitle, List<String> variables, String maskedUrl) {
        public SampleView { variables = List.copyOf(variables); }
    }
    public record Result(List<StageView> stages, int totalEntries, List<SampleView> samples, List<String> warnings) {
        public Result { stages = List.copyOf(stages); samples = List.copyOf(samples); warnings = List.copyOf(warnings); }
    }
    private final WorkflowStore store;
    private final LoginService login;
    private final WorkflowHttpClient http;

    public WorkflowTestService(WorkflowStore store, LoginService login, WorkflowHttpClient http) {
        this.store = store; this.login = login; this.http = http;
    }

    public Result test(String id, long revision, HttpServletRequest request) {
        authenticate(request);
        WorkflowDefinition saved = current(id, revision);
        List<StageView> stages = new ArrayList<>();
        List<SampleView> samples = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int total = 0;
        Stage active = Stage.FETCH;
        try {
            byte[] body = http.fetch(saved.draft().fetch());
            stages.add(ok(Stage.FETCH)); active = Stage.PARSE;
            var root = WorkflowJson.parse(body);
            stages.add(ok(Stage.PARSE)); active = Stage.SELECT;
            var entries = WorkflowJson.entries(saved.draft(), root);
            total = entries.size(); stages.add(ok(Stage.SELECT));
            var draft = saved.draft();
            if (draft.mode() == WorkflowDraft.Mode.GENERATED && draft.listing().artworkPointer() != null) {
                boolean omitted = entries.stream().anyMatch(entry -> {
                    var selected = entry.node().at(draft.listing().artworkPointer());
                    return entry.artwork() == null && !selected.isMissingNode() && !selected.isNull();
                });
                if (omitted) warnings.add("Some artwork was omitted because it is not a public HTTPS image address without credentials.");
            }
            active = Stage.BUILD;
            var template = new WorkflowTemplate(draft.cast().template(), draft.variables().stream()
                    .map(WorkflowDraft.Variable::name).collect(Collectors.toSet()));
            for (var entry : entries.stream().limit(5).toList()) {
                active = Stage.MAP;
                var values = WorkflowJson.values(draft.variables(), root, entry.node());
                var masked = draft.variables().stream().map(variable -> variable.name() + " = "
                        + (variable.sensitive() ? "•••" : values.get(variable.name()).text())).toList();
                active = Stage.BUILD;
                http.checkMedia(template.expand(values));
                samples.add(new SampleView(entry.title(), entry.subtitle(), masked, template.preview(values)));
            }
            stages.add(ok(Stage.MAP)); stages.add(ok(Stage.BUILD));
            warnings.add("Your receiver must reach the media address directly. Custom media-download headers are not supported.");
        } catch (RuntimeException failure) {
            Stage failed = failure instanceof WorkflowException e ? e.stage() : active;
            // WorkflowException's contract allows only locally authored safe context.
            // Never expose messages or causes from parser, network or other exceptions.
            String message = failure instanceof WorkflowException ? failure.getMessage()
                    : "Could not complete this step. Check the saved settings and response format.";
            stages.add(new StageView(label(failed), false, message));
            samples.clear();
        }
        authenticate(request);
        current(id, revision); // An explicit Test may run while disabled, but never return an obsolete revision.
        return new Result(stages, total, samples, warnings);
    }

    private void authenticate(HttpServletRequest request) {
        if (!login.isAuthenticated(request)) throw new LoginRequiredException();
    }
    private WorkflowDefinition current(String id, long revision) {
        var saved = store.find(id).orElseThrow(WorkflowTestService::changed);
        if (saved.revision() != revision) throw changed();
        return saved;
    }
    private static WorkflowException changed() { return new WorkflowException(Stage.WORKFLOW, "Workflow changed; reopen this item"); }
    private static StageView ok(Stage stage) { return new StageView(label(stage), true, "Complete"); }
    private static String label(Stage stage) {
        return switch (stage) {
            case FETCH -> "Fetch JSON"; case PARSE -> "Parse JSON"; case SELECT -> "Choose entries";
            case MAP -> "Map fields"; case BUILD -> "Build media URL"; case WORKFLOW -> "Workflow";
        };
    }
}
