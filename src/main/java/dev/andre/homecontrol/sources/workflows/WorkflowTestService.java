package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.security.LoginContext;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** Explicit, authenticated preview. Only the returned display strings may leave this service. */
@Service
@ConditionalOnModule(Module.WORKFLOWS)
public final class WorkflowTestService {
    public record StageView(String name, boolean success, String message) {}
    public record SampleView(String title, String subtitle, List<String> variables, String maskedUrl) {
        public SampleView { variables = List.copyOf(variables); }
    }
    public record Result(List<StageView> stages, int totalEntries, List<SampleView> samples, List<String> warnings) {
        public Result { stages = List.copyOf(stages); samples = List.copyOf(samples); warnings = List.copyOf(warnings); }
    }
    private static final String RECEIVER = "Your receiver must reach the media address directly. Custom media-download headers are not supported.";
    private static final String ARTWORK = "Some artwork was omitted because it is not a public HTTPS image address without credentials.";
    private static final int SAMPLES = 5;
    private final WorkflowStore store;
    private final WorkflowRunner runner;
    private final WorkflowHttpClient http;

    public WorkflowTestService(WorkflowStore store, WorkflowRunner runner, WorkflowHttpClient http) {
        this.store = store; this.runner = runner; this.http = http;
    }

    public Result test(String id, long revision, LoginContext browser) {
        browser.requireLogin();
        WorkflowDefinition saved = current(id, revision);
        var draft = saved.draft();
        List<StageView> stages = new ArrayList<>();
        List<SampleView> samples = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int total = 0;
        String active = "Refresh";
        try {
            if (draft.mode() == WorkflowDraft.Mode.GENERATED) {
                var refresh = runner.refresh(saved, runner.refreshRun());
                total = refresh.entries().size();
                stages.add(new StageView("Refresh", true, total + " entries"));
                warnings.addAll(refresh.problems());
                if (refresh.artworkOmitted()) warnings.add(ARTWORK);
            }
            active = "Play";
            var run = runner.playRun();
            var context = runner.play(saved, run);
            if (draft.mode() == WorkflowDraft.Mode.SINGLE) total = 1;
            var template = new WorkflowTemplate(draft.cast().template(), Set.copyOf(context.plan().variables()));
            for (var entry : context.entries().stream().limit(SAMPLES).toList()) {
                var values = runner.entryValues(saved, context, entry, run);
                http.checkMedia(runner.media(saved, context.plan(), values), run.deadline());
                samples.add(new SampleView(entry.title(), entry.subtitle(), masked(context.plan(), values), template.preview(values)));
            }
            stages.add(new StageView("Play", true, "Complete"));
            warnings.add(RECEIVER);
        } catch (RuntimeException failure) {
            // WorkflowException's contract allows only locally authored safe context.
            // Never expose messages or causes from parser, network or other exceptions.
            boolean safe = failure instanceof WorkflowException;
            String name = failure instanceof WorkflowException known && known.call() != null ? "Call " + known.call() : active;
            String message = safe ? failure.getMessage() : "Could not complete this step. Check the saved settings and response format.";
            stages.add(new StageView(name, false, message));
            samples.clear();
        }
        browser.requireLogin();
        current(id, revision); // An explicit Test may run while disabled, but never return an obsolete revision.
        return new Result(stages, total, samples, warnings);
    }

    private static List<String> masked(WorkflowPlan plan, Map<String, WorkflowJson.Value> values) {
        return plan.variables().stream().filter(values::containsKey)
                .map(name -> name + " = " + (plan.sensitive(name) ? "\u2022\u2022\u2022" : values.get(name).text())).toList();
    }

    private WorkflowDefinition current(String id, long revision) {
        var saved = store.find(id).orElseThrow(WorkflowTestService::changed);
        if (saved.revision() != revision) throw changed();
        return saved;
    }
    private static WorkflowException changed() { return new WorkflowException(Stage.WORKFLOW, "Workflow changed; reopen this item"); }
}
