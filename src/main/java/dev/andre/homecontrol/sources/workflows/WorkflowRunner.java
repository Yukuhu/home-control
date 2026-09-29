package dev.andre.homecontrol.sources.workflows;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static dev.andre.homecontrol.sources.workflows.WorkflowDraft.*;

/** Refreshes tiles and resolves a Play from a workflow's calls. Responses and secret values stay inside one run. */
public final class WorkflowRunner {
    static final int ENTRY_LIMIT = 200;
    static final int ENTRY_LIMIT_WITH_CALLS = 50;
    private static final int REFRESH_PARALLEL = 3;
    private static final int PLAY_PARALLEL = 4;
    private static final Executor VIRTUAL = task -> Thread.ofVirtual().name("workflow-entry").start(task);
    private final WorkflowHttpClient http;
    private final WorkflowCalls calls;
    private final WorkflowProperties properties;

    public WorkflowRunner(WorkflowHttpClient http, WorkflowProperties properties) {
        this.http = http;
        this.calls = new WorkflowCalls(http);
        this.properties = properties;
    }

    public record CatalogEntry(String key, String title, String subtitle, URI artwork) {}

    public record ResolvedMedia(URI url, String mimeType, String title) {
        @Override public String toString() { return "ResolvedMedia[redacted]"; }
    }

    /** A refresh's tiles, the per-entry failures that left tiles without extra fields, and whether artwork was unsafe. */
    record Refresh(List<CatalogEntry> entries, List<String> problems, boolean artworkOmitted) {}

    /** What a Play fetched before choosing an entry. */
    record PlayContext(WorkflowPlan plan, Map<String, WorkflowJson.Value> shared, List<WorkflowJson.Entry> entries) {
        @Override public String toString() { return "PlayContext[redacted]"; }
    }

    private record Tiled(CatalogEntry entry, String problem, boolean artworkOmitted) {}

    WorkflowCalls.Run refreshRun() { return new WorkflowCalls.Run(properties.refreshTimeout(), REFRESH_PARALLEL); }

    WorkflowCalls.Run playRun() { return new WorkflowCalls.Run(properties.playTimeout(), PLAY_PARALLEL); }

    public List<CatalogEntry> catalog(WorkflowDefinition definition) {
        return refresh(definition, refreshRun()).entries();
    }

    Refresh refresh(WorkflowDefinition definition, WorkflowCalls.Run run) {
        var draft = definition.draft();
        var plan = WorkflowPlan.of(draft);
        var listing = draft.listing();
        var shared = calls.run(plan.refreshCalls(CallScope.SHARED), plan, Map.of(), run, null);
        var entryCalls = plan.refreshCalls(CallScope.ENTRY);
        var entries = WorkflowJson.entries(listing, shared.responses().get(listing.call()), limit(plan));
        var entryValues = listing.variables().stream().filter(v -> plan.refreshEntryValues().contains(v.name())).toList();
        var tiles = entries.stream().map(entry -> CompletableFuture.supplyAsync(
                () -> tile(entry, plan, listing, entryValues, shared.values(), entryCalls, run), VIRTUAL)).toList();
        List<Tiled> done = tiles.stream().map(CompletableFuture::join).toList();
        return new Refresh(done.stream().map(Tiled::entry).toList(),
                done.stream().map(Tiled::problem).filter(Objects::nonNull).toList(),
                done.stream().anyMatch(Tiled::artworkOmitted));
    }

    private Tiled tile(WorkflowJson.Entry entry, WorkflowPlan plan, Listing listing, List<Variable> entryValues,
                       Map<String, WorkflowJson.Value> shared, List<Call> entryCalls, WorkflowCalls.Run run) {
        Map<String, WorkflowJson.Value> values = shared;
        String problem = null;
        try {
            var known = new HashMap<>(shared);
            known.putAll(WorkflowJson.values(entryValues, entry.node()));
            values = calls.run(entryCalls, plan, known, run, entry.title()).values();
        } catch (WorkflowException failure) {
            problem = failure.call() != null ? failure.getMessage() : "Entry \"" + entry.title() + "\": " + failure.detail();
        }
        String subtitle = subtitle(listing.subtitle(), entry.subtitle(), values);
        URI artwork = artwork(listing.artwork(), entry.artwork(), values);
        boolean omitted = artwork == null && hasArtworkText(listing.artwork(), entry, values);
        return new Tiled(new CatalogEntry(entry.key(), entry.title(), subtitle, artwork), problem, omitted);
    }

    private static String subtitle(Field field, String fromPointer, Map<String, WorkflowJson.Value> values) {
        if (field == null) return null;
        if (field.variable() == null) return fromPointer;
        WorkflowJson.Value value = values.get(field.variable());
        return value != null && value.text().length() <= 240 ? value.text() : null;
    }

    private static URI artwork(Field field, URI fromPointer, Map<String, WorkflowJson.Value> values) {
        if (field == null) return null;
        if (field.variable() == null) return fromPointer;
        WorkflowJson.Value value = values.get(field.variable());
        return value == null ? null : WorkflowJson.artwork(value.text());
    }

    /** True when an artwork address was there but was not a public HTTPS image address. */
    private static boolean hasArtworkText(Field field, WorkflowJson.Entry entry, Map<String, WorkflowJson.Value> values) {
        if (field == null) return false;
        if (field.variable() != null) return values.containsKey(field.variable());
        var node = entry.node() == null ? null : entry.node().at(field.pointer());
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    public ResolvedMedia resolve(WorkflowDefinition definition, String entryKey) {
        var run = playRun();
        var context = play(definition, run);
        var entry = context.entries().stream().filter(e -> e.key().equals(entryKey)).findFirst()
                .orElseThrow(() -> new WorkflowException(WorkflowException.Stage.SELECT,
                        "This item is no longer available; refresh the Dashboard"));
        var values = entryValues(definition, context, entry, run);
        URI url = media(definition, context.plan(), values);
        http.checkMedia(url, run.deadline());
        return new ResolvedMedia(url, definition.draft().cast().mimeType(), entry.title());
    }

    PlayContext play(WorkflowDefinition definition, WorkflowCalls.Run run) {
        var draft = definition.draft();
        var plan = WorkflowPlan.of(draft);
        var shared = calls.run(plan.playCalls(CallScope.SHARED), plan, Map.of(), run, null);
        List<WorkflowJson.Entry> entries = draft.mode() == Mode.SINGLE ? List.of(single(draft.tile()))
                : WorkflowJson.entries(draft.listing(), shared.responses().get(draft.listing().call()), limit(plan));
        return new PlayContext(plan, shared.values(), entries);
    }

    Map<String, WorkflowJson.Value> entryValues(WorkflowDefinition definition, PlayContext context,
                                                WorkflowJson.Entry entry, WorkflowCalls.Run run) {
        var draft = definition.draft();
        var known = new HashMap<>(context.shared());
        String title = null;
        if (draft.mode() == Mode.GENERATED) {
            Set<String> needed = context.plan().playEntryValues();
            known.putAll(WorkflowJson.values(draft.listing().variables().stream()
                    .filter(v -> needed.contains(v.name())).toList(), entry.node()));
            title = entry.title();
        }
        return calls.run(context.plan().playCalls(CallScope.ENTRY), context.plan(), known, run, title).values();
    }

    URI media(WorkflowDefinition definition, WorkflowPlan plan, Map<String, WorkflowJson.Value> values) {
        return new WorkflowTemplate(definition.draft().cast().template(), Set.copyOf(plan.variables())).expand(values);
    }

    /** Play must see the same entries as the refresh that built the tiles, so both use this limit. */
    private static int limit(WorkflowPlan plan) {
        return plan.refreshCalls(CallScope.ENTRY).isEmpty() ? ENTRY_LIMIT : ENTRY_LIMIT_WITH_CALLS;
    }

    private static WorkflowJson.Entry single(Tile tile) {
        return new WorkflowJson.Entry("single", tile.title(), tile.subtitle(), WorkflowJson.artwork(tile.artwork()), null);
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
