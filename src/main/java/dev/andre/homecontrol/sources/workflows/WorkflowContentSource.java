package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.content.RailPreferences;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Local single tiles and generated catalogs. Opening an item never performs workflow I/O. */
public final class WorkflowContentSource implements ContentSource {
    public static final String SOURCE_ID = "workflows";
    private final WorkflowStore store;
    private final WorkflowRunner runner;
    private final WorkflowCatalogs catalogs;
    private final RailPreferences preferences;

    public WorkflowContentSource(WorkflowStore store, WorkflowRunner runner, WorkflowCatalogs catalogs,
                                 RailPreferences preferences) {
        this.store = store;
        this.runner = runner;
        this.catalogs = catalogs;
        this.preferences = preferences;
    }

    @Override public String id() { return SOURCE_ID; }
    @Override public String displayName() { return "Workflows"; }
    @Override public boolean available() { return preferences.sourceEnabled(SOURCE_ID) && store.all().stream().anyMatch(d -> d.draft().enabled()); }
    @Override public List<RailDescriptor> rails() {
        if (!preferences.sourceEnabled(SOURCE_ID)) return List.of();
        return store.all().stream().filter(d -> d.draft().enabled()).map(WorkflowContentSource::descriptor).toList();
    }

    @Override public Rail rail(String railId) {
        try {
            requireSource();
            var definition = store.find(railId).filter(d -> d.draft().enabled()).orElseThrow(WorkflowContentSource::changed);
            List<ContentItem> items;
            if (definition.draft().mode() == WorkflowDraft.Mode.SINGLE) {
                items = List.of(single(definition));
                store.ifCurrent(definition.id(), definition.revision(), () -> {});
            } else {
                long generation = catalogs.begin(definition.id());
                var entries = runner.catalog(definition);
                requireSource();
                if (!catalogs.publish(definition, generation, entries)) throw changed();
                items = WorkflowCatalogs.items(definition, entries);
            }
            return new Rail(descriptor(definition), items, Instant.now());
        } catch (WorkflowException failure) {
            throw new ContentSourceException(failure.getMessage());
        }
    }

    @Override public Optional<ContentItem> item(String itemId) {
        if (!preferences.sourceEnabled(SOURCE_ID) || itemId == null) return Optional.empty();
        if (itemId.matches("w-[0-9a-f]{12}")) {
            return store.find(itemId).filter(d -> d.draft().enabled() && d.draft().mode() == WorkflowDraft.Mode.SINGLE)
                    .map(WorkflowContentSource::single);
        }
        return catalogs.find(itemId);
    }

    private void requireSource() {
        if (!preferences.sourceEnabled(SOURCE_ID)) throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "source is disabled");
    }

    private static ContentItem single(WorkflowDefinition definition) {
        var tile = definition.draft().tile();
        return WorkflowCatalogs.items(definition, List.of(new WorkflowRunner.CatalogEntry("single", tile.title(),
                tile.subtitle(), WorkflowJson.artwork(tile.artwork())))).getFirst();
    }

    private static RailDescriptor descriptor(WorkflowDefinition definition) {
        return new RailDescriptor(SOURCE_ID, definition.id(), definition.draft().name());
    }

    private static WorkflowException changed() {
        return new WorkflowException(WorkflowException.Stage.WORKFLOW, "Workflow changed; reopen this item");
    }
}
