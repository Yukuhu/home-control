package dev.andre.homecontrol.sources.workflows;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** One response belongs to one call; neither raw JSON nor resolved secrets enter the catalog. */
public final class WorkflowRunner {
    static final int ENTRY_LIMIT = 200;
    private final WorkflowHttpClient http;

    public WorkflowRunner(WorkflowHttpClient http) { this.http = http; }

    public record CatalogEntry(String key, String title, String subtitle, URI artwork) {}

    public record ResolvedMedia(URI url, String mimeType, String title) {
        @Override public String toString() { return "ResolvedMedia[redacted]"; }
    }

    public List<CatalogEntry> catalog(WorkflowDefinition definition) {
        var draft = definition.draft();
        var root = fetch(draft.calls().getFirst());
        return WorkflowJson.entries(draft.listing(), root, ENTRY_LIMIT).stream()
                .map(entry -> new CatalogEntry(entry.key(), entry.title(), entry.subtitle(), entry.artwork())).toList();
    }

    public ResolvedMedia resolve(WorkflowDefinition definition, String entryKey) {
        var draft = definition.draft();
        var call = draft.calls().getFirst();
        var root = fetch(call);
        Map<String, WorkflowJson.Value> values = new HashMap<>(WorkflowJson.values(call.variables(), root));
        String title = draft.mode() == Mode.SINGLE ? draft.tile().title() : null;
        if (draft.mode() == Mode.GENERATED) {
            var selected = WorkflowJson.entries(draft.listing(), root, ENTRY_LIMIT).stream()
                    .filter(entry -> entry.key().equals(entryKey)).findFirst()
                    .orElseThrow(() -> new WorkflowException(WorkflowException.Stage.SELECT,
                            "entry disappeared; refresh this workflow"));
            values.putAll(WorkflowJson.values(draft.listing().variables(), selected.node()));
            title = selected.title();
        }
        URI url = new WorkflowTemplate(draft.cast().template(), values.keySet()).expand(values);
        http.checkMedia(url);
        return new ResolvedMedia(url, draft.cast().mimeType(), title);
    }

    private JsonNode fetch(Call call) {
        return WorkflowJson.parse(http.fetch(request(call, Set.of(), Map.of())));
    }

    /** The GET a call makes, with the values known so far substituted into its URL and headers. */
    static WorkflowHttpClient.Request request(Call call, Set<String> names, Map<String, WorkflowJson.Value> values) {
        URI url = new WorkflowTemplate(call.url(), names, "call URL", WorkflowException.Stage.FETCH).expand(values);
        List<Header> headers = call.headers().stream()
                .map(header -> new Header(header.name(), new WorkflowHeaderTemplate(header.value()).expand(values)))
                .toList();
        return new WorkflowHttpClient.Request(url.toString(), headers);
    }
}
