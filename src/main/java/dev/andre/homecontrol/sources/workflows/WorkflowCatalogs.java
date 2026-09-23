package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Revision-tagged display metadata; a later-started load always supersedes an older load. */
public final class WorkflowCatalogs {
    private final WorkflowStore store;
    private final Map<String, Long> generations = new HashMap<>();
    private final Map<String, Map<String, ContentItem>> snapshots = new HashMap<>();
    private long sequence;

    public WorkflowCatalogs(WorkflowStore store) { this.store = store; }

    public synchronized long begin(String workflowId) {
        long generation = ++sequence;
        generations.put(workflowId, generation);
        return generation;
    }

    public boolean publish(WorkflowDefinition definition, long generation, List<WorkflowRunner.CatalogEntry> entries) {
        List<ContentItem> items = items(definition, entries);
        var snapshot = new HashMap<String, ContentItem>();
        items.forEach(item -> snapshot.put(item.id(), item));
        Map<String, ContentItem> immutable = Map.copyOf(snapshot);
        var published = new AtomicBoolean();
        try {
            // Lock order is store, then catalogs; never call the store while holding this monitor.
            store.ifCurrent(definition.id(), definition.revision(), () -> {
                synchronized (this) {
                    if (!Long.valueOf(generation).equals(generations.get(definition.id()))) return;
                    snapshots.put(definition.id(), immutable);
                    published.set(true);
                }
            });
        } catch (WorkflowException changed) {
            return false;
        }
        return published.get();
    }

    public Optional<ContentItem> find(String itemId) {
        if (itemId == null || !itemId.matches("w-[0-9a-f]{12}\\.[0-9a-f]{64}")) return Optional.empty();
        String workflowId = itemId.substring(0, 14);
        ContentItem item;
        synchronized (this) {
            item = snapshots.getOrDefault(workflowId, Map.of()).get(itemId);
        }
        if (item == null) return Optional.empty();
        var ref = (PlayableRef.WorkflowCast) item.playables().getFirst();
        return store.find(workflowId).filter(definition -> definition.draft().enabled()
                        && definition.revision() == ref.revision())
                .map(ignored -> item);
    }

    public synchronized void invalidate() {
        generations.clear();
        snapshots.clear();
        // Keep sequence monotonic: tokens issued before invalidation must never become current again.
    }

    static List<ContentItem> items(WorkflowDefinition definition, List<WorkflowRunner.CatalogEntry> entries) {
        return entries.stream().map(entry -> new ContentItem(
                definition.draft().mode() == WorkflowDraft.Mode.SINGLE ? definition.id() : definition.id() + "." + entry.key(),
                WorkflowContentSource.SOURCE_ID, definition.draft().kind(), entry.title(), entry.subtitle(), entry.artwork(),
                List.of(new PlayableRef.WorkflowCast(definition.id(), definition.revision(), entry.key())))).toList();
    }
}
