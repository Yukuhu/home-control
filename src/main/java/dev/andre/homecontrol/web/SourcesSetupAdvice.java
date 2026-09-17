package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.content.StoredRailPreferences;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.core.content.StreamingProviders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.ArrayList;
import java.util.List;

/** Tells the setup page which sources, rails and streaming services to show (spec D4). */
@ControllerAdvice(assignableTypes = SetupController.class)
public class SourcesSetupAdvice {

    private final ObjectProvider<SourcePreferencesService> preferences;
    private final ObjectProvider<StoredRailPreferences> rails;
    private final ObjectProvider<ContentSources> sources;

    public SourcesSetupAdvice(ObjectProvider<SourcePreferencesService> preferences,
                              ObjectProvider<StoredRailPreferences> rails, ObjectProvider<ContentSources> sources) {
        this.preferences = preferences;
        this.rails = rails;
        this.sources = sources;
    }

    @ModelAttribute("sourcesSetup")
    public SourcesSetupView sourcesSetup() {
        SourcePreferencesService prefsService = preferences.getIfAvailable();
        StoredRailPreferences railPreferences = rails.getIfAvailable();
        ContentSources contentSources = sources.getIfAvailable();
        if (prefsService == null || railPreferences == null || contentSources == null) {
            return null;
        }
        SourcePreferences current = prefsService.current();
        List<ContentSource> all = contentSources.all();

        List<SourcesSetupView.SourceRow> sourceRows = all.stream()
                .map(source -> new SourcesSetupView.SourceRow(source.id(), source.displayName(), source.available(),
                        !current.disabledSources().contains(source.id()), source.searchable(),
                        current.refreshMinutes().get(source.id()),
                        railPreferences.defaultRefreshInterval(source).toMinutes()))
                .toList();

        List<RailDescriptor> ordered = railPreferences.allRailsInOrder(all);
        List<SourcesSetupView.RailRow> railRows = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            RailDescriptor descriptor = ordered.get(i);
            String key = descriptor.sourceId() + "/" + descriptor.id();
            String sourceName = contentSources.find(descriptor.sourceId()).map(ContentSource::displayName)
                    .orElse(descriptor.sourceId());
            railRows.add(new SourcesSetupView.RailRow(key, descriptor.title(), sourceName,
                    !current.hiddenRails().contains(key), i == 0, i == ordered.size() - 1));
        }

        List<SourcesSetupView.ProviderRow> providerRows = StreamingProviders.KNOWN.entrySet().stream()
                .map(entry -> new SourcesSetupView.ProviderRow(entry.getKey(), entry.getValue(),
                        current.providers().contains(entry.getKey())))
                .toList();

        return new SourcesSetupView(sourceRows, railRows, current.locale(), current.region(), providerRows);
    }
}
