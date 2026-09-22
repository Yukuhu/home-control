package dev.andre.homecontrol.sources.workflows;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/** One response belongs to one call; neither raw JSON nor resolved secrets enter the catalog. */
public final class WorkflowRunner {
    private final WorkflowHttpClient http;

    public WorkflowRunner(WorkflowHttpClient http) { this.http = http; }

    public record CatalogEntry(String key, String title, String subtitle, URI artwork) {}

    public record ResolvedMedia(URI url, String mimeType, String title) {
        @Override public String toString() { return "ResolvedMedia[redacted]"; }
    }

    public List<CatalogEntry> catalog(WorkflowDefinition definition) {
        var draft = definition.draft();
        var root = WorkflowJson.parse(http.fetch(draft.fetch()));
        return WorkflowJson.entries(draft, root).stream()
                .map(entry -> new CatalogEntry(entry.key(), entry.title(), entry.subtitle(), entry.artwork())).toList();
    }

    public ResolvedMedia resolve(WorkflowDefinition definition, String entryKey) {
        var draft = definition.draft();
        var root = WorkflowJson.parse(http.fetch(draft.fetch()));
        var selected = WorkflowJson.entries(draft, root).stream().filter(entry -> entry.key().equals(entryKey))
                .findFirst().orElseThrow(() -> new WorkflowException(WorkflowException.Stage.SELECT,
                        "entry disappeared; refresh this workflow"));
        var values = WorkflowJson.values(draft.variables(), root, selected.node());
        var template = new WorkflowTemplate(draft.cast().template(),
                draft.variables().stream().map(WorkflowDraft.Variable::name).collect(Collectors.toSet()));
        URI url = template.expand(values);
        http.checkMedia(url);
        return new ResolvedMedia(url, draft.cast().mimeType(), selected.title());
    }
}
