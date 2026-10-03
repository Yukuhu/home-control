package dev.andre.homecontrol.core.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Every enabled content source, in bean order. */
public class ContentSources {

    private static final System.Logger LOG = System.getLogger(ContentSources.class.getName());
    /** The failure last logged per source id. */
    private static final Map<String, String> FAILING = new ConcurrentHashMap<>();

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

    /**
     * Whether a source has anything to show. One whose check fails has not, so that a bug or an unreadable store in one
     * source cannot take search, the dashboard or the setup page down with it.
     */
    public static boolean available(ContentSource source) {
        try {
            boolean available = source.available();
            FAILING.remove(source.id());
            return available;
        } catch (RuntimeException e) {
            reportOnce(source, e);
            return false;
        }
    }

    /** A source's rails; none when asking for them fails, for the same reason as {@link #available}. */
    public static List<RailDescriptor> rails(ContentSource source) {
        try {
            return source.rails();
        } catch (RuntimeException e) {
            reportOnce(source, e);
            return List.of();
        }
    }

    /** Every page view asks again; a failure is logged when it first happens or changes, not on every view. */
    private static void reportOnce(ContentSource source, RuntimeException failure) {
        String message = String.valueOf(failure.getMessage());
        if (!message.equals(FAILING.put(source.id(), message))) {
            LOG.log(System.Logger.Level.WARNING, "Leaving out the content source " + source.id() + ": " + message,
                    failure);
        }
    }

    public List<ContentSource> searchable() {
        return byId.values().stream().filter(s -> s.searchable() && available(s)).toList();
    }
}
