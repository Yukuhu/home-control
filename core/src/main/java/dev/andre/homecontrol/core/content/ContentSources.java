package dev.andre.homecontrol.core.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every enabled content source, in bean order. */
public class ContentSources {

    private final Map<String, ContentSource> byId = new LinkedHashMap<>();

    public ContentSources(List<ContentSource> sources) {
        for (ContentSource source : sources) {
            if (byId.putIfAbsent(source.id(), source) != null) {
                throw new IllegalStateException("Two content sources share the id " + source.id());
            }
        }
    }

    public List<ContentSource> all() {
        return List.copyOf(byId.values());
    }

    public Optional<ContentSource> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<ContentSource> searchable() {
        return byId.values().stream().filter(s -> s.searchable() && s.available()).toList();
    }
}
